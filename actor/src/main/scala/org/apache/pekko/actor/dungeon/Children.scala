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

package org.apache.pekko.actor.dungeon

import java.util.Optional

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import scala.annotation.nowarn
import org.apache.pekko
import pekko.actor._
import pekko.annotation.InternalStableApi
import pekko.serialization.{ Serialization, SerializationExtension, Serializers }
import pekko.util.{ Helpers, Unsafe }

private[pekko] object Children {
  val GetNobody = () => Nobody
}

private[pekko] trait Children { this: ActorCell =>

  import ChildrenContainer._

  @nowarn("msg=never used|is never updated")
  @volatile
  private var _childrenRefsDoNotCallMeDirectly: ChildrenContainer = EmptyChildrenContainer

  def childrenRefs: ChildrenContainer =
    Unsafe.instance.getObjectVolatile(this, AbstractActorCell.childrenOffset).asInstanceOf[ChildrenContainer]: @nowarn(
      "cat=deprecation")

  final def children: immutable.Iterable[ActorRef] = childrenRefs.children
  @nowarn("msg=deprecated")
  final def getChildren(): java.lang.Iterable[ActorRef] = {
    import pekko.util.ccompat.JavaConverters._
    children.asJava
  }

  final def child(name: String): Option[ActorRef] = Option(getChild(name))
  final def getChild(name: String): ActorRef = childrenRefs.getByName(name) match {
    case Some(s: ChildRestartStats) => s.child
    case _                          => null
  }
  def findChild(name: String): Optional[ActorRef] = Optional.ofNullable(getChild(name))

  def actorOf(props: Props): ActorRef =
    makeChild(this, props, randomName(), async = false, systemService = false)
  def actorOf(props: Props, name: String): ActorRef =
    makeChild(this, props, checkName(name), async = false, systemService = false)
  private[pekko] def attachChild(props: Props, systemService: Boolean): ActorRef =
    makeChild(this, props, randomName(), async = true, systemService = systemService)
  private[pekko] def attachChild(props: Props, name: String, systemService: Boolean): ActorRef =
    makeChild(this, props, checkName(name), async = true, systemService = systemService)

  @nowarn @volatile private var _functionRefsDoNotCallMeDirectly = Map.empty[String, FunctionRef]
  private def functionRefs: Map[String, FunctionRef] =
    Unsafe.instance.getObjectVolatile(this, AbstractActorCell.functionRefsOffset).asInstanceOf[Map[String,
      FunctionRef]]: @nowarn("cat=deprecation")

  private[pekko] def getFunctionRefOrNobody(name: String, uid: Int = ActorCell.undefinedUid): InternalActorRef =
    functionRefs.getOrElse(name, Children.GetNobody()) match {
      case f: FunctionRef =>
        if (uid == ActorCell.undefinedUid || f.path.uid == uid) f else Nobody
      case other =>
        other
    }

  private[pekko] def addFunctionRef(f: (ActorRef, Any) => Unit, name: String = ""): FunctionRef = {
    val r = randomName(new java.lang.StringBuilder("$$"))
    val n = if (name != "") s"$r-$name" else r
    val childPath = new ChildActorPath(self.path, n, ActorCell.newUid())
    val ref = new FunctionRef(childPath, provider, system, f)

    @tailrec def rec(): Unit = {
      val old = functionRefs
      val added = old.updated(childPath.name, ref)
      if (!Unsafe.instance.compareAndSwapObject(this, AbstractActorCell.functionRefsOffset, old, added): @nowarn(
          "cat=deprecation")) rec()
    }
    rec()

    ref
  }

  private[pekko] def removeFunctionRef(ref: FunctionRef): Boolean = {
    require(ref.path.parent eq self.path, "trying to remove FunctionRef from wrong ActorCell")
    val name = ref.path.name
    @tailrec def rec(): Boolean = {
      val old = functionRefs
      if (!old.contains(name)) false
      else {
        val removed = old - name
        if (!Unsafe.instance.compareAndSwapObject(this, AbstractActorCell.functionRefsOffset, old, removed): @nowarn(
            "cat=deprecation")) rec()
        else {
          ref.stop()
          true
        }
      }
    }
    rec()
  }

  protected def stopFunctionRefs(): Unit = {
    val refs = Unsafe.instance
      .getAndSetObject(this, AbstractActorCell.functionRefsOffset, Map.empty)
      .asInstanceOf[Map[String, FunctionRef]]: @nowarn("cat=deprecation")
    refs.valuesIterator.foreach(_.stop())
  }

  @nowarn @volatile private var _nextNameDoNotCallMeDirectly = 0L
  final protected def randomName(sb: java.lang.StringBuilder): String = {
    val num = Unsafe.instance.getAndAddLong(this, AbstractActorCell.nextNameOffset, 1): @nowarn("cat=deprecation")
    Helpers.base64(num, sb)
  }
  final protected def randomName(): String = {
    val num = Unsafe.instance.getAndAddLong(this, AbstractActorCell.nextNameOffset, 1): @nowarn("cat=deprecation")
    Helpers.base64(num)
  }

  final def stop(actor: ActorRef): Unit = {
    if (childrenRefs.getByRef(actor).isDefined) {
      @tailrec def shallDie(ref: ActorRef): Boolean = {
        val c = childrenRefs
        swapChildrenRefs(c, c.shallDie(ref)) || shallDie(ref)
      }

      if (actor match {
          case r: RepointableRef => r.isStarted
          case _                 => true
        }) shallDie(actor)
    }
    actor.asInstanceOf[InternalActorRef].stop()
  }

  @nowarn private def _preventPrivateUnusedErasure = {
    _childrenRefsDoNotCallMeDirectly
    _functionRefsDoNotCallMeDirectly
    _nextNameDoNotCallMeDirectly
  }

  /**
   * low level CAS helpers
   */
  private final def swapChildrenRefs(oldChildren: ChildrenContainer, newChildren: ChildrenContainer): Boolean =
    Unsafe.instance.compareAndSwapObject(this, AbstractActorCell.childrenOffset, oldChildren, newChildren): @nowarn(
      "cat=deprecation")

  @tailrec final def reserveChild(name: String): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.reserve(name)) || reserveChild(name)
  }

  @tailrec final protected def unreserveChild(name: String): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.unreserve(name)) || unreserveChild(name)
  }

  @tailrec final def initChild(ref: ActorRef): Option[ChildRestartStats] = {
    val cc = childrenRefs
    cc.getByName(ref.path.name) match {
      case old @ Some(_: ChildRestartStats) => old.asInstanceOf[Option[ChildRestartStats]]
      case Some(ChildNameReserved)          =>
        val crs = ChildRestartStats(ref)
        val name = ref.path.name
        if (swapChildrenRefs(cc, cc.add(name, crs))) Some(crs) else initChild(ref)
      case None => None
    }
  }

  @tailrec final protected def setChildrenTerminationReason(reason: ChildrenContainer.SuspendReason): Boolean = {
    childrenRefs match {
      case c: ChildrenContainer.TerminatingChildrenContainer =>
        swapChildrenRefs(c, c.copy(reason = reason)) || setChildrenTerminationReason(reason)
      case _ => false
    }
  }

  final protected def setTerminated(): Unit =
    Unsafe.instance.putObjectVolatile(this, AbstractActorCell.childrenOffset, TerminatedChildrenContainer): @nowarn(
      "cat=deprecation")

  /*
   * ActorCell-internal API
   */

  protected def isNormal = childrenRefs.isNormal

  protected def isTerminating = childrenRefs.isTerminating

  protected def waitingForChildrenOrNull = childrenRefs match {
    case TerminatingChildrenContainer(_, _, w: WaitingForChildren) => w
    case _                                                         => null
  }

  @InternalStableApi
  protected def suspendChildren(exceptFor: Set[ActorRef] = Set.empty): Unit =
    childrenRefs.stats.foreach {
      case ChildRestartStats(child, _, _) if !(exceptFor contains child) =>
        child.asInstanceOf[InternalActorRef].suspend()
      case _ =>
    }

  protected def resumeChildren(causedByFailure: Throwable, perp: ActorRef): Unit =
    childrenRefs.stats.foreach {
      case ChildRestartStats(child: InternalActorRef, _, _) =>
        child.resume(if (perp == child) causedByFailure else null)
      case stats =>
        throw new IllegalStateException(s"Unexpected child ActorRef: ${stats.child}")
    }

  def getChildByName(name: String): Option[ChildStats] = childrenRefs.getByName(name)

  protected def getChildByRef(ref: ActorRef): Option[ChildRestartStats] = childrenRefs.getByRef(ref)

  protected def getAllChildStats: immutable.Iterable[ChildRestartStats] = childrenRefs.stats

  override def getSingleChild(name: String): InternalActorRef =
    if (name.indexOf('#') == -1) {
      // optimization for the non-uid case
      getChildByName(name) match {
        case Some(crs: ChildRestartStats) => crs.child.asInstanceOf[InternalActorRef]
        case _                            => getFunctionRefOrNobody(name)
      }
    } else {
      val (childName, uid) = ActorCell.splitNameAndUid(name)
      getChildByName(childName) match {
        case Some(crs: ChildRestartStats) if uid == ActorCell.undefinedUid || uid == crs.uid =>
          crs.child.asInstanceOf[InternalActorRef]
        case _ => getFunctionRefOrNobody(childName, uid)
      }
    }

  protected def removeChildAndGetStateChange(child: ActorRef): Option[SuspendReason] = {
    @tailrec def removeChild(ref: ActorRef): ChildrenContainer = {
      val c = childrenRefs
      val n = c.remove(ref)
      if (swapChildrenRefs(c, n)) n else removeChild(ref)
    }

    childrenRefs match { // The match must be performed BEFORE the removeChild
      case TerminatingChildrenContainer(_, _, reason) =>
        removeChild(child) match {
          case _: TerminatingChildrenContainer => None
          case _                               => Some(reason)
        }
      case _ =>
        removeChild(child)
        None
    }
  }

  /*
   * Private helpers
   */

  private def checkName(name: String): String = {
    name match {
      case null => throw InvalidActorNameException("actor name must not be null")
      case ""   => throw InvalidActorNameException("actor name must not be empty")
      case _    =>
        ActorPath.validatePathElement(name)
        name
    }
  }

  private def makeChild(
      cell: ActorCell,
      props: Props,
      name: String,
      async: Boolean,
      systemService: Boolean): ActorRef = {
    val settings = cell.system.settings
    if (settings.SerializeAllCreators && !systemService && props.deploy.scope != LocalScope) {
      val oldInfo = Serialization.currentTransportInformation.value
      try {
        val ser = SerializationExtension(cell.system)
        if (oldInfo eq null)
          Serialization.currentTransportInformation.value = system.provider.serializationInformation

        props.args.forall(arg =>
          arg == null ||
          arg.isInstanceOf[NoSerializationVerificationNeeded] ||
          settings.NoSerializationVerificationNeededClassPrefix.exists(arg.getClass.getName.startsWith) || {
            val o = arg.asInstanceOf[AnyRef]
            val serializer = ser.findSerializerFor(o)
            val bytes = serializer.toBinary(o)
            val ms = Serializers.manifestFor(serializer, o)
            ser.deserialize(bytes, serializer.identifier, ms).get != null
          })
      } catch {
        case NonFatal(e) =>
          throw new IllegalArgumentException(s"pre-creation serialization check failed at [${cell.self.path}/$name]", e)
      } finally Serialization.currentTransportInformation.value = oldInfo
    }

    /*
     * in case we are currently terminating, fail external attachChild requests
     * (internal calls cannot happen anyway because we are suspended)
     */
    if (cell.childrenRefs.isTerminating)
      throw new IllegalStateException("cannot create children while terminating or terminated")
    else {
      reserveChild(name)
      // this name will either be unreserved or overwritten with a real child below
      val actor =
        try {
          val childPath = new ChildActorPath(cell.self.path, name, ActorCell.newUid())
          cell.provider.actorOf(
            cell.systemImpl,
            props,
            cell.self,
            childPath,
            systemService = systemService,
            deploy = None,
            lookupDeploy = true,
            async = async)
        } catch {
          case e: InterruptedException =>
            unreserveChild(name)
            Thread.interrupted() // clear interrupted flag before throwing according to java convention
            throw e
          case NonFatal(e) =>
            unreserveChild(name)
            throw e
        }
      // mailbox==null during RoutedActorCell constructor, where suspends are queued otherwise
      if (mailbox ne null) for (_ <- 1 to mailbox.suspendCount) actor.suspend()
      initChild(actor)
      actor.start()
      actor
    }
  }

}
