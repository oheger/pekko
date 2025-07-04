/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.dispatch

import java.lang.reflect.ParameterizedType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import com.typesafe.config.{ Config, ConfigFactory }

import org.apache.pekko
import pekko.ConfigurationException
import pekko.actor.{ Actor, ActorRef, ActorSystem, DeadLetter, Deploy, DynamicAccess, Props }
import pekko.annotation.InternalStableApi
import pekko.dispatch.sysmsg.{
  EarliestFirstSystemMessageList,
  LatestFirstSystemMessageList,
  SystemMessage,
  SystemMessageList
}
import pekko.event.EventStream
import pekko.event.Logging.Warning
import pekko.util.Reflect

object Mailboxes {
  final val DefaultMailboxId = "pekko.actor.default-mailbox"
  final val NoMailboxRequirement = ""
  final val BoundedCapacityPrefix = "bounded-capacity:"
}

private[pekko] class Mailboxes(
    val settings: ActorSystem.Settings,
    val eventStream: EventStream,
    dynamicAccess: DynamicAccess,
    deadLetters: ActorRef) {

  import Mailboxes._

  val deadLetterMailbox: Mailbox = new Mailbox(new MessageQueue {
    def enqueue(receiver: ActorRef, envelope: Envelope): Unit = onDeadLetterMailboxEnqueue(receiver, envelope)
    def dequeue() = null
    def hasMessages = false
    def numberOfMessages = 0
    def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = ()
  }) {
    becomeClosed()
    def systemEnqueue(receiver: ActorRef, handle: SystemMessage): Unit =
      deadLetters ! DeadLetter(handle, receiver, receiver)
    def systemDrain(newContents: LatestFirstSystemMessageList): EarliestFirstSystemMessageList = SystemMessageList.ENil
    def hasSystemMessages = false
  }

  @InternalStableApi
  private[pekko] def onDeadLetterMailboxEnqueue(receiver: ActorRef, envelope: Envelope): Unit = envelope.message match {
    case _: DeadLetter => // actor subscribing to DeadLetter, drop it
    case msg           => deadLetters.tell(DeadLetter(msg, envelope.sender, receiver), envelope.sender)
  }

  private val mailboxTypeConfigurators = new ConcurrentHashMap[String, MailboxType]

  private val mailboxBindings: Map[Class[_ <: Any], String] = {
    import pekko.util.ccompat.JavaConverters._
    settings.config
      .getConfig("pekko.actor.mailbox.requirements")
      .root
      .unwrapped
      .asScala
      .toMap
      .foldLeft(Map.empty[Class[_ <: Any], String]) {
        case (m, (k, v)) =>
          dynamicAccess
            .getClassFor[Any](k)
            .map { x =>
              m.updated(x, v.toString)
            }
            .recover {
              case e =>
                throw new ConfigurationException(
                  s"Type [$k] specified as pekko.actor.mailbox.requirement " +
                  s"[$v] in config can't be loaded due to [${e.getMessage}]",
                  e)
            }
            .get
      }
  }

  /**
   * Returns a mailbox type as specified in configuration, based on the id, or if not defined None.
   */
  def lookup(id: String): MailboxType = lookupConfigurator(id)

  /**
   * Returns a mailbox type as specified in configuration, based on the type, or if not defined None.
   */
  def lookupByQueueType(queueType: Class[_ <: Any]): MailboxType = lookup(lookupId(queueType))

  private final val rmqClass = classOf[RequiresMessageQueue[_]]

  /**
   * Return the required message queue type for this class if any.
   */
  def getRequiredType(actorClass: Class[_ <: Actor]): Class[_] =
    Reflect.findMarker(actorClass, rmqClass) match {
      case t: ParameterizedType =>
        t.getActualTypeArguments.head match {
          case c: Class[_] => c
          case x           =>
            throw new IllegalArgumentException(s"no wildcard type allowed in RequireMessageQueue argument (was [$x])")
        }
      case unexpected =>
        throw new IllegalArgumentException(s"Unexpected actor class marker: $unexpected") // will not happen, for exhaustiveness check
    }

  // don’t care if this happens twice
  private var mailboxSizeWarningIssued = false
  private var mailboxNonZeroPushTimeoutWarningIssued = false

  def getMailboxRequirement(config: Config) = config.getString("mailbox-requirement") match {
    case NoMailboxRequirement => classOf[MessageQueue]
    case x                    => dynamicAccess.getClassFor[AnyRef](x).get
  }

  def getProducedMessageQueueType(mailboxType: MailboxType): Class[_] = {
    val pmqClass = classOf[ProducesMessageQueue[_]]
    if (!pmqClass.isAssignableFrom(mailboxType.getClass)) classOf[MessageQueue]
    else
      Reflect.findMarker(mailboxType.getClass, pmqClass) match {
        case t: ParameterizedType =>
          t.getActualTypeArguments.head match {
            case c: Class[_] => c
            case x           =>
              throw new IllegalArgumentException(
                s"no wildcard type allowed in ProducesMessageQueue argument (was [$x])")
          }
        case unexpected =>
          throw new IllegalArgumentException(s"Unexpected message queue type marker: $unexpected") // will not happen, for exhaustiveness check
      }
  }

  /**
   * Finds out the mailbox type for an actor based on configuration, props and requirements.
   */
  protected[pekko] def getMailboxType(props: Props, dispatcherConfig: Config): MailboxType = {
    val id = dispatcherConfig.getString("id")
    val deploy = props.deploy
    val actorClass = props.actorClass()
    lazy val actorRequirement = getRequiredType(actorClass)

    val mailboxRequirement: Class[_] = getMailboxRequirement(dispatcherConfig)

    val hasMailboxRequirement: Boolean = mailboxRequirement != classOf[MessageQueue]

    val hasMailboxType =
      dispatcherConfig.hasPath("mailbox-type") &&
      dispatcherConfig.getString("mailbox-type") != Deploy.NoMailboxGiven

    // TODO remove in Akka 2.3
    if (!hasMailboxType && !mailboxSizeWarningIssued && dispatcherConfig.hasPath("mailbox-size")) {
      eventStream.publish(
        Warning(
          "mailboxes",
          getClass,
          s"ignoring setting 'mailbox-size' for dispatcher [$id], you need to specify 'mailbox-type=bounded'"))
      mailboxSizeWarningIssued = true
    }

    def verifyRequirements(mailboxType: MailboxType): MailboxType = {
      lazy val mqType: Class[_] = getProducedMessageQueueType(mailboxType)
      if (hasMailboxRequirement && !mailboxRequirement.isAssignableFrom(mqType))
        throw new IllegalArgumentException(
          s"produced message queue type [$mqType] does not fulfill requirement for dispatcher [$id]. " +
          s"Must be a subclass of [$mailboxRequirement].")
      if (hasRequiredType(actorClass) && !actorRequirement.isAssignableFrom(mqType))
        throw new IllegalArgumentException(
          s"produced message queue type [$mqType] does not fulfill requirement for actor class [$actorClass]. " +
          s"Must be a subclass of [$actorRequirement].")
      mailboxType
    }

    if (deploy.mailbox != Deploy.NoMailboxGiven) {
      verifyRequirements(lookup(deploy.mailbox))
    } else if (deploy.dispatcher != Deploy.NoDispatcherGiven && deploy.dispatcher != Deploy
        .DispatcherSameAsParent && hasMailboxType) {
      verifyRequirements(lookup(dispatcherConfig.getString("id")))
    } else if (hasRequiredType(actorClass)) {
      try verifyRequirements(lookupByQueueType(getRequiredType(actorClass)))
      catch {
        case NonFatal(_) if hasMailboxRequirement => verifyRequirements(lookupByQueueType(mailboxRequirement))
      }
    } else if (hasMailboxRequirement) {
      verifyRequirements(lookupByQueueType(mailboxRequirement))
    } else {
      verifyRequirements(lookup(DefaultMailboxId))
    }
  }

  /**
   * Check if this class can have a required message queue type.
   */
  def hasRequiredType(actorClass: Class[_ <: Actor]): Boolean = rmqClass.isAssignableFrom(actorClass)

  private def lookupId(queueType: Class[_]): String =
    mailboxBindings.get(queueType) match {
      case None    => throw new ConfigurationException(s"Mailbox Mapping for [$queueType] not configured")
      case Some(s) => s
    }

  private def lookupConfigurator(id: String): MailboxType = {
    mailboxTypeConfigurators.get(id) match {
      case null =>
        // It doesn't matter if we create a mailbox type configurator that isn't used due to concurrent lookup.
        val newConfigurator = id match {
          // TODO RK remove these two for Akka 2.3
          case "unbounded"                               => UnboundedMailbox()
          case "bounded"                                 => new BoundedMailbox(settings, config(id))
          case _ if id.startsWith(BoundedCapacityPrefix) =>
            // hack to allow programmatic set of capacity through props in pekko-typed but still share
            // mailbox configurators for the same size
            val capacity = id.split(':')(1).toInt
            new BoundedMailbox(capacity, Duration.Zero)

          case _ =>
            if (!settings.config.hasPath(id)) throw new ConfigurationException(s"Mailbox Type [$id] not configured")
            val conf = config(id)

            val mailboxType = conf.getString("mailbox-type") match {
              case ""   => throw new ConfigurationException(s"The setting mailbox-type, defined in [$id] is empty")
              case fqcn =>
                val args = List(classOf[ActorSystem.Settings] -> settings, classOf[Config] -> conf)
                dynamicAccess
                  .createInstanceFor[MailboxType](fqcn, args)
                  .recover {
                    case exception =>
                      throw new IllegalArgumentException(
                        s"Cannot instantiate MailboxType [$fqcn], defined in [$id], make sure it has a public" +
                        " constructor with [org.apache.pekko.actor.ActorSystem.Settings, com.typesafe.config.Config] parameters",
                        exception)
                  }
                  .get
            }

            if (!mailboxNonZeroPushTimeoutWarningIssued) {
              mailboxType match {
                case m: ProducesPushTimeoutSemanticsMailbox if m.pushTimeOut.toNanos > 0L =>
                  warn(
                    s"Configured potentially-blocking mailbox [$id] configured with non-zero pushTimeOut (${m.pushTimeOut}), " +
                    s"which can lead to blocking behavior when sending messages to this mailbox. " +
                    s"Avoid this by setting `$id.mailbox-push-timeout-time` to `0`.")
                  mailboxNonZeroPushTimeoutWarningIssued = true
                case _ => // good; nothing to see here, move along, sir.
              }
            }

            mailboxType
        }

        mailboxTypeConfigurators.putIfAbsent(id, newConfigurator) match {
          case null     => newConfigurator
          case existing => existing
        }

      case existing => existing
    }
  }

  private val defaultMailboxConfig = settings.config.getConfig(DefaultMailboxId)

  private final def warn(msg: String): Unit =
    eventStream.publish(Warning("mailboxes", getClass, msg))

  // INTERNAL API
  private def config(id: String): Config = {
    import pekko.util.ccompat.JavaConverters._
    ConfigFactory
      .parseMap(Map("id" -> id).asJava)
      .withFallback(settings.config.getConfig(id))
      .withFallback(defaultMailboxConfig)
  }

  private val stashCapacityCache = new AtomicReference[Map[String, Int]](Map.empty[String, Int])
  private val defaultStashCapacity: Int =
    stashCapacityFromConfig(Dispatchers.DefaultDispatcherId, Mailboxes.DefaultMailboxId)

  /**
   * INTERNAL API: The capacity of the stash. Configured in the actor's mailbox or dispatcher config.
   */
  private[pekko] final def stashCapacity(dispatcher: String, mailbox: String): Int = {

    @tailrec def updateCache(cache: Map[String, Int], key: String, value: Int): Boolean = {
      stashCapacityCache.compareAndSet(cache, cache.updated(key, value)) ||
      updateCache(stashCapacityCache.get, key, value) // recursive, try again
    }

    if (dispatcher == Dispatchers.DefaultDispatcherId && mailbox == Mailboxes.DefaultMailboxId)
      defaultStashCapacity
    else {
      val cache = stashCapacityCache.get
      val key = dispatcher + "-" + mailbox
      cache.get(key) match {
        case Some(value) => value
        case None        =>
          val value = stashCapacityFromConfig(dispatcher, mailbox)
          updateCache(cache, key, value)
          value
      }
    }
  }

  private def stashCapacityFromConfig(dispatcher: String, mailbox: String): Int = {
    val disp = Dispatchers.getConfig(settings.config, dispatcher)
    val fallback = disp.withFallback(settings.config.getConfig(Mailboxes.DefaultMailboxId))
    val config =
      if (mailbox == Mailboxes.DefaultMailboxId) fallback
      else settings.config.getConfig(mailbox).withFallback(fallback)
    config.getInt("stash-capacity")
  }
}
