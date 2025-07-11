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

import java.{ util => ju }
import java.util.concurrent._

import scala.annotation.{ nowarn, tailrec }
import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor }
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.control.NonFatal

import org.apache.pekko
import pekko.actor._
import pekko.annotation.InternalStableApi
import pekko.dispatch.affinity.AffinityPoolConfigurator
import pekko.dispatch.sysmsg._
import pekko.event.EventStream
import pekko.event.Logging.{ emptyMDC, Debug, Error, LogEventException, Warning }
import pekko.util.{ unused, Index, Unsafe }

import com.typesafe.config.Config

final case class Envelope private (message: Any, sender: ActorRef) {

  @noinline // not inlined to permit downstream bytecode instrumentation to attach context information to the Envelope
  def copy(message: Any = message, sender: ActorRef = sender) = {
    Envelope(message, sender)
  }
}

object Envelope {
  def apply(message: Any, sender: ActorRef, system: ActorSystem): Envelope = {
    if (message == null) {
      if (sender eq Actor.noSender)
        throw InvalidMessageException(s"Message is null.")
      else
        throw InvalidMessageException(s"Message sent from [$sender] is null.")
    }
    new Envelope(message, if (sender ne Actor.noSender) sender else system.deadLetters)
  }
}

final case class TaskInvocation(eventStream: EventStream, runnable: Runnable, cleanup: () => Unit) extends Batchable {
  final override def isBatchable: Boolean = pekko.dispatch.internal.ScalaBatchable.isBatchable(runnable)

  def run(): Unit =
    try runnable.run()
    catch {
      case NonFatal(e) => eventStream.publish(Error(e, "TaskInvocation", this.getClass, e.getMessage))
    } finally cleanup()
}

/**
 * INTERNAL API
 */
private[pekko] trait LoadMetrics { self: Executor =>
  def atFullThrottle(): Boolean
}

/**
 * INTERNAL API
 */
private[pekko] object MessageDispatcher {
  val UNSCHEDULED = 0 // WARNING DO NOT CHANGE THE VALUE OF THIS: It relies on the faster init of 0 in AbstractMessageDispatcher
  val SCHEDULED = 1
  val RESCHEDULED = 2

  // dispatcher debugging helper using println (see below)
  // since this is a compile-time constant, scalac will elide code behind if (MessageDispatcher.debug) (RK checked with 2.9.1)
  final val debug = false // Deliberately without type ascription to make it a compile-time constant
  lazy val actors = new Index[MessageDispatcher, ActorRef](16,
    new ju.Comparator[ActorRef] {
      override def compare(a: ActorRef, b: ActorRef): Int = a.compareTo(b)
    })
  def printActors(): Unit =
    if (debug) {
      for {
        d <- actors.keys
        a <- { println("" + d + " inhabitants: " + d.inhabitants); actors.valueIterator(d) }
      } {
        val status = if (a.isTerminated) " (terminated)" else " (alive)"
        val messages = a match {
          case r: ActorRefWithCell => " " + r.underlying.numberOfMessages + " messages"
          case _                   => " " + a.getClass
        }
        val parent = a match {
          case i: InternalActorRef => ", parent: " + i.getParent
          case _                   => ""
        }
        println(" -> " + a + status + messages + parent)
      }
    }
}

abstract class MessageDispatcher(val configurator: MessageDispatcherConfigurator)
    extends AbstractMessageDispatcher
    with BatchingExecutor
    with ExecutionContextExecutor {

  import AbstractMessageDispatcher.{ inhabitantsOffset, shutdownScheduleOffset }
  import MessageDispatcher._
  import configurator.prerequisites

  val mailboxes = prerequisites.mailboxes
  val eventStream = prerequisites.eventStream

  @nowarn @volatile private[this] var _inhabitantsDoNotCallMeDirectly: Long = _ // DO NOT TOUCH!
  @nowarn @volatile private[this] var _shutdownScheduleDoNotCallMeDirectly: Int = _ // DO NOT TOUCH!
  @nowarn private def _preventPrivateUnusedErasure = {
    _inhabitantsDoNotCallMeDirectly
    _shutdownScheduleDoNotCallMeDirectly
  }

  private final def addInhabitants(add: Long): Long = {
    val old = Unsafe.instance.getAndAddLong(this, inhabitantsOffset, add): @nowarn("cat=deprecation")
    val ret = old + add
    if (ret < 0) {
      // We haven't succeeded in decreasing the inhabitants yet but the simple fact that we're trying to
      // go below zero means that there is an imbalance and we might as well throw the exception
      val e = new IllegalStateException("ACTOR SYSTEM CORRUPTED!!! A dispatcher can't have less than 0 inhabitants!")
      reportFailure(e)
      throw e
    }
    ret
  }

  final def inhabitants: Long = Unsafe.instance.getLongVolatile(this, inhabitantsOffset): @nowarn("cat=deprecation")

  private final def shutdownSchedule: Int =
    Unsafe.instance.getIntVolatile(this, shutdownScheduleOffset): @nowarn("cat=deprecation")
  private final def updateShutdownSchedule(expect: Int, update: Int): Boolean =
    Unsafe.instance.compareAndSwapInt(this, shutdownScheduleOffset, expect, update): @nowarn("cat=deprecation")

  /**
   *  Creates and returns a mailbox for the given actor.
   */
  protected[pekko] def createMailbox(actor: Cell, mailboxType: MailboxType): Mailbox

  /**
   * Identifier of this dispatcher, corresponds to the full key
   * of the dispatcher configuration.
   */
  def id: String

  /**
   * Attaches the specified actor instance to this dispatcher, which includes
   * scheduling it to run for the first time (Create() is expected to have
   * been enqueued by the ActorCell upon mailbox creation).
   */
  final def attach(actor: ActorCell): Unit = {
    register(actor)
    registerForExecution(actor.mailbox, false, true)
  }

  /**
   * Detaches the specified actor instance from this dispatcher
   */
  final def detach(actor: ActorCell): Unit =
    try unregister(actor)
    finally ifSensibleToDoSoThenScheduleShutdown()
  final protected def resubmitOnBlock: Boolean = true // We want to avoid starvation
  final override protected def unbatchedExecute(r: Runnable): Unit = {
    val invocation = TaskInvocation(eventStream, r, taskCleanup)
    addInhabitants(+1)
    try {
      executeTask(invocation)
    } catch {
      case t: Throwable =>
        addInhabitants(-1)
        throw t
    }
  }

  override def reportFailure(t: Throwable): Unit = t match {
    case e: LogEventException => eventStream.publish(e.event)
    case _                    => eventStream.publish(Error(t, getClass.getName, getClass, t.getMessage))
  }

  @tailrec
  private final def ifSensibleToDoSoThenScheduleShutdown(): Unit = {
    if (inhabitants <= 0) shutdownSchedule match {
      case UNSCHEDULED =>
        if (updateShutdownSchedule(UNSCHEDULED, SCHEDULED)) scheduleShutdownAction()
        else ifSensibleToDoSoThenScheduleShutdown()
      case SCHEDULED =>
        if (updateShutdownSchedule(SCHEDULED, RESCHEDULED)) ()
        else ifSensibleToDoSoThenScheduleShutdown()
      case RESCHEDULED =>
      case unexpected  =>
        throw new IllegalArgumentException(s"Unexpected actor class marker: $unexpected") // will not happen, for exhaustiveness check
    }
  }

  private def scheduleShutdownAction(): Unit = {
    // IllegalStateException is thrown if scheduler has been shutdown
    try prerequisites.scheduler.scheduleOnce(shutdownTimeout, shutdownAction)(new ExecutionContext {
        override def execute(runnable: Runnable): Unit = runnable.run()
        override def reportFailure(t: Throwable): Unit = MessageDispatcher.this.reportFailure(t)
      })
    catch {
      case _: IllegalStateException =>
        shutdown()
        // Since there is no scheduler anymore, restore the state to UNSCHEDULED.
        // When this dispatcher is used again,
        // shutdown is only attempted if the state is UNSCHEDULED
        // (as per ifSensibleToDoSoThenScheduleShutdown above)
        updateShutdownSchedule(SCHEDULED, UNSCHEDULED)
    }
  }

  private final val taskCleanup: () => Unit = () => if (addInhabitants(-1) == 0) ifSensibleToDoSoThenScheduleShutdown()

  /**
   * If you override it, you must call it. But only ever once. See "attach" for only invocation.
   *
   * INTERNAL API
   */
  protected[pekko] def register(actor: ActorCell): Unit = {
    if (debug) actors.put(this, actor.self)
    addInhabitants(+1)
  }

  /**
   * If you override it, you must call it. But only ever once. See "detach" for the only invocation
   *
   * INTERNAL API
   */
  protected[pekko] def unregister(actor: ActorCell): Unit = {
    if (debug) actors.remove(this, actor.self)
    addInhabitants(-1)
    val mailBox = actor.swapMailbox(mailboxes.deadLetterMailbox)
    mailBox.becomeClosed()
    mailBox.cleanUp()
  }

  private val shutdownAction = new Scheduler.TaskRunOnClose {
    @tailrec
    final def run(): Unit = {
      shutdownSchedule match {
        case SCHEDULED =>
          try {
            if (inhabitants == 0) shutdown() // Warning, racy
          } finally {
            while (!updateShutdownSchedule(shutdownSchedule, UNSCHEDULED)) {}
          }
        case RESCHEDULED =>
          if (updateShutdownSchedule(RESCHEDULED, SCHEDULED)) scheduleShutdownAction()
          else run()
        case UNSCHEDULED =>
        case unexpected  =>
          throw new IllegalArgumentException(s"Unexpected actor class marker: $unexpected") // will not happen, for exhaustiveness check
      }
    }
  }

  /**
   * When the dispatcher no longer has any actors registered, how long will it wait until it shuts itself down,
   * defaulting to your pekko configs "pekko.actor.default-dispatcher.shutdown-timeout" or default specified in
   * reference.conf
   *
   * INTERNAL API
   */
  protected[pekko] def shutdownTimeout: FiniteDuration

  /**
   * After the call to this method, the dispatcher mustn't begin any new message processing for the specified reference
   */
  protected[pekko] def suspend(actor: ActorCell): Unit = {
    val mbox = actor.mailbox
    if ((mbox.actor eq actor) && (mbox.dispatcher eq this))
      mbox.suspend()
  }

  /**
   * After the call to this method, the dispatcher must begin any new message processing for the specified reference
   */
  protected[pekko] def resume(actor: ActorCell): Unit = {
    val mbox = actor.mailbox
    if ((mbox.actor eq actor) && (mbox.dispatcher eq this) && mbox.resume())
      registerForExecution(mbox, false, false)
  }

  /**
   * Will be called when the dispatcher is to queue an invocation for execution
   *
   * INTERNAL API
   */
  protected[pekko] def systemDispatch(receiver: ActorCell, invocation: SystemMessage): Unit

  /**
   * Will be called when the dispatcher is to queue an invocation for execution
   *
   * INTERNAL API
   */
  protected[pekko] def dispatch(receiver: ActorCell, invocation: Envelope): Unit

  /**
   * Suggest to register the provided mailbox for execution
   *
   * INTERNAL API
   */
  protected[pekko] def registerForExecution(
      mbox: Mailbox,
      hasMessageHint: Boolean,
      hasSystemMessageHint: Boolean): Boolean

  // TODO check whether this should not actually be a property of the mailbox
  /**
   * INTERNAL API
   */
  protected[pekko] def throughput: Int

  /**
   * INTERNAL API
   */
  protected[pekko] def throughputDeadlineTime: Duration

  /**
   * INTERNAL API
   */
  protected[pekko] final val isThroughputDeadlineTimeDefined = throughputDeadlineTime.toMillis > 0

  /**
   * INTERNAL API
   */
  protected[pekko] def executeTask(invocation: TaskInvocation): Unit

  /**
   * Called one time every time an actor is detached from this dispatcher and this dispatcher has no actors left attached
   * Must be idempotent
   *
   * INTERNAL API
   */
  @InternalStableApi
  protected[pekko] def shutdown(): Unit
}

/**
 * An ExecutorServiceConfigurator is a class that given some prerequisites and a configuration can create instances of ExecutorService
 */
abstract class ExecutorServiceConfigurator(@unused config: Config, @unused prerequisites: DispatcherPrerequisites)
    extends ExecutorServiceFactoryProvider

/**
 * Base class to be used for hooking in new dispatchers into Dispatchers.
 */
abstract class MessageDispatcherConfigurator(_config: Config, val prerequisites: DispatcherPrerequisites) {

  val config: Config = new CachingConfig(_config)

  /**
   * Returns an instance of MessageDispatcher given the configuration.
   * Depending on the needs the implementation may return a new instance for
   * each invocation or return the same instance every time.
   */
  def dispatcher(): MessageDispatcher

  def configureExecutor(): ExecutorServiceConfigurator = {
    @tailrec
    def configurator(executor: String): ExecutorServiceConfigurator = executor match {
      case null | "" | "fork-join-executor" =>
        new ForkJoinExecutorConfigurator(config.getConfig("fork-join-executor"), prerequisites)
      case "virtual-thread-executor" =>
        if (VirtualThreadSupport.isSupported) {
          new VirtualThreadExecutorConfigurator(config.getConfig("virtual-thread-executor"), prerequisites)
        } else {
          configurator(config.getString("virtual-thread-executor.fallback"))
        }
      case "thread-pool-executor" =>
        new ThreadPoolExecutorConfigurator(config.getConfig("thread-pool-executor"), prerequisites)
      case "affinity-pool-executor" =>
        new AffinityPoolConfigurator(config.getConfig("affinity-pool-executor"), prerequisites)

      case fqcn =>
        val args = List(classOf[Config] -> config, classOf[DispatcherPrerequisites] -> prerequisites)
        prerequisites.dynamicAccess
          .createInstanceFor[ExecutorServiceConfigurator](fqcn, args)
          .recover {
            case exception =>
              throw new IllegalArgumentException(
                """Cannot instantiate ExecutorServiceConfigurator ("executor = [%s]"), defined in [%s],
                make sure it has an accessible constructor with a [%s,%s] signature"""
                  .format(fqcn, config.getString("id"), classOf[Config], classOf[DispatcherPrerequisites]),
                exception)
          }
          .get
    }

    config.getString("executor") match {
      case "default-executor" =>
        new DefaultExecutorServiceConfigurator(
          config.getConfig("default-executor"),
          prerequisites,
          configurator(config.getString("default-executor.fallback")))
      case other => configurator(other)
    }
  }
}

final class VirtualThreadExecutorConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
    extends ExecutorServiceConfigurator(config, prerequisites) {

  override def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = {
    import VirtualThreadSupport._
    val tf: ThreadFactory = threadFactory match {
      case MonitorableThreadFactory(name, _, contextClassLoader, exceptionHandler, _) =>
        new ThreadFactory {
          private val vtFactory = newVirtualThreadFactory(name + "-" + id)

          override def newThread(r: Runnable): Thread = {
            val vt = vtFactory.newThread(r)
            vt.setUncaughtExceptionHandler(exceptionHandler)
            contextClassLoader.foreach(vt.setContextClassLoader)
            vt
          }
        }
      case _ => newVirtualThreadFactory(prerequisites.settings.name + "-" + id);
    }
    new ExecutorServiceFactory {
      override def createExecutorService: ExecutorService with LoadMetrics = {
        // try to get the default scheduler of virtual thread
        val pool = {
          try {
            getVirtualThreadDefaultScheduler
          } catch {
            case NonFatal(e) =>
              prerequisites.eventStream.publish(
                Warning(e, "VirtualThreadExecutorConfigurator", this.getClass,
                  """
                  |Failed to get the default scheduler of virtual thread, so the `LoadMetrics` is not available when using it with `BalancingDispatcher`.
                  |Add `--add-opens java.base/java.lang=ALL-UNNAMED` to the JVM options to help this.
                  |""".stripMargin, emptyMDC))
              null
          }
        }
        val loadMetricsProvider: Executor => Boolean = {
          if (pool eq null) {
            (_: Executor) => true
          } else {
            (_: Executor) => pool.getActiveThreadCount >= pool.getParallelism
          }
        }
        new VirtualizedExecutorService(
          tf, // the virtual thread factory
          pool, // the default scheduler of virtual thread
          loadMetricsProvider,
          cascadeShutdown = false // we don't want to cascade shutdown the default virtual thread scheduler
        )
      }
    }
  }
}

class ThreadPoolExecutorConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
    extends ExecutorServiceConfigurator(config, prerequisites) {

  val threadPoolConfig: ThreadPoolConfig = createThreadPoolConfigBuilder(config, prerequisites).config

  protected def createThreadPoolConfigBuilder(
      config: Config,
      @unused prerequisites: DispatcherPrerequisites): ThreadPoolConfigBuilder = {
    import org.apache.pekko.util.Helpers.ConfigOps
    val builder =
      ThreadPoolConfigBuilder(ThreadPoolConfig())
        .setKeepAliveTime(config.getMillisDuration("keep-alive-time"))
        .setAllowCoreThreadTimeout(config.getBoolean("allow-core-timeout"))
        .configure(Some(config.getInt("task-queue-size")).flatMap {
          case size if size > 0 =>
            Some(config.getString("task-queue-type"))
              .map {
                case "array"       => ThreadPoolConfig.arrayBlockingQueue(size, false) // TODO config fairness?
                case "" | "linked" => ThreadPoolConfig.linkedBlockingQueue(size)
                case x             =>
                  throw new IllegalArgumentException("[%s] is not a valid task-queue-type [array|linked]!".format(x))
              }
              .map { qf => (q: ThreadPoolConfigBuilder) =>
                q.setQueueFactory(qf)
              }
          case _ => None
        })

    if (config.getString("fixed-pool-size") == "off")
      builder
        .setCorePoolSizeFromFactor(
          config.getInt("core-pool-size-min"),
          config.getDouble("core-pool-size-factor"),
          config.getInt("core-pool-size-max"))
        .setMaxPoolSizeFromFactor(
          config.getInt("max-pool-size-min"),
          config.getDouble("max-pool-size-factor"),
          config.getInt("max-pool-size-max"))
    else
      builder.setFixedPoolSize(config.getInt("fixed-pool-size"))
  }

  def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory =
    threadPoolConfig.createExecutorServiceFactory(id, threadFactory)
}

class DefaultExecutorServiceConfigurator(
    config: Config,
    prerequisites: DispatcherPrerequisites,
    fallback: ExecutorServiceConfigurator)
    extends ExecutorServiceConfigurator(config, prerequisites) {
  val provider: ExecutorServiceFactoryProvider =
    prerequisites.defaultExecutionContext match {
      case Some(ec) =>
        prerequisites.eventStream.publish(
          Debug(
            "DefaultExecutorServiceConfigurator",
            this.getClass,
            s"Using passed in ExecutionContext as default executor for this ActorSystem. If you want to use a different executor, please specify one in pekko.actor.default-dispatcher.default-executor."))

        new AbstractExecutorService with ExecutorServiceFactory with ExecutorServiceFactoryProvider {
          def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = this
          def createExecutorService: ExecutorService = this
          def shutdown(): Unit = ()
          def isTerminated: Boolean = false
          def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
          def shutdownNow(): ju.List[Runnable] = ju.Collections.emptyList()
          def execute(command: Runnable): Unit = ec.execute(command)
          def isShutdown: Boolean = false
        }
      case None => fallback
    }

  def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory =
    provider.createExecutorServiceFactory(id, threadFactory)
}
