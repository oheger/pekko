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

package org.apache.pekko.remote

import scala.concurrent.Future
import scala.util.Failure
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal

import scala.annotation.nowarn

import org.apache.pekko
import pekko.ConfigurationException
import pekko.Done
import pekko.actor._
import pekko.actor.SystemGuardian.RegisterTerminationHook
import pekko.actor.SystemGuardian.TerminationHook
import pekko.actor.SystemGuardian.TerminationHookDone
import pekko.annotation.InternalApi
import pekko.dispatch.RequiresMessageQueue
import pekko.dispatch.UnboundedMessageQueueSemantics
import pekko.dispatch.sysmsg._
import pekko.event.EventStream
import pekko.event.Logging
import pekko.event.Logging.Error
import pekko.event.LoggingAdapter
import pekko.pattern.pipe
import pekko.remote.artery.ArterySettings
import pekko.remote.artery.ArterySettings.AeronUpd
import pekko.remote.artery.ArteryTransport
import pekko.remote.artery.OutboundEnvelope
import pekko.remote.artery.SystemMessageDelivery.SystemMessageEnvelope
import pekko.remote.artery.aeron.ArteryAeronUdpTransport
import pekko.remote.artery.tcp.ArteryTcpTransport
import pekko.remote.serialization.ActorRefResolveThreadLocalCache
import pekko.serialization.Serialization
import pekko.util.ErrorMessages
import pekko.util.OptionVal
import pekko.util.unused

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] object RemoteActorRefProvider {

  private final case class Internals(transport: RemoteTransport, remoteDaemon: InternalActorRef)
      extends NoSerializationVerificationNeeded

  sealed trait TerminatorState
  case object Uninitialized extends TerminatorState
  case object Idle extends TerminatorState
  case object WaitDaemonShutdown extends TerminatorState
  case object WaitTransportShutdown extends TerminatorState
  case object Finished extends TerminatorState

  private class RemotingTerminator(systemGuardian: ActorRef)
      extends Actor
      with FSM[TerminatorState, Option[Internals]]
      with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
    import context.dispatcher

    startWith(Uninitialized, None)

    when(Uninitialized) {
      case Event(i: Internals, _) =>
        systemGuardian ! RegisterTerminationHook
        goto(Idle).using(Some(i))
    }

    when(Idle) {
      case Event(TerminationHook, Some(internals)) =>
        log.info("Shutting down remote daemon.")
        internals.remoteDaemon ! TerminationHook
        goto(WaitDaemonShutdown)
    }

    // TODO: state timeout
    when(WaitDaemonShutdown) {
      case Event(TerminationHookDone, Some(internals)) =>
        log.info("Remote daemon shut down; proceeding with flushing remote transports.")
        internals.transport.shutdown().pipeTo(self)
        goto(WaitTransportShutdown)
    }

    when(WaitTransportShutdown) {
      case Event(Done, _) =>
        log.info("Remoting shut down.")
        systemGuardian ! TerminationHookDone
        stop()

      case Event(Status.Failure(ex), _) =>
        log.error(ex, "Remoting shut down with error")
        systemGuardian ! TerminationHookDone
        stop()
    }

  }

  /**
   * Remoting wraps messages destined to a remote host in a remoting specific envelope: EndpointManager.Send
   * As these wrapped messages might arrive to the dead letters of an EndpointWriter, they need to be unwrapped
   * and handled as dead letters to the original (remote) destination. Without this special case, DeathWatch related
   * functionality breaks, like the special handling of Watch messages arriving to dead letters.
   */
  private class RemoteDeadLetterActorRef(_provider: ActorRefProvider, _path: ActorPath, _eventStream: EventStream)
      extends DeadLetterActorRef(_provider, _path, _eventStream) {
    import EndpointManager.Send

    // Still supports classic remoting as well
    @nowarn("msg=Classic remoting is deprecated, use Artery")
    override def !(message: Any)(implicit sender: ActorRef): Unit = message match {
      case Send(m, senderOption, recipient, seqOpt) =>
        // else ignore: it is a reliably delivered message that might be retried later, and it has not yet deserved
        // the dead letter status
        if (seqOpt.isEmpty) super.!(DeadLetter(m, senderOption.getOrElse(_provider.deadLetters), recipient))
      case DeadLetter(Send(m, senderOption, recipient, seqOpt), _, _) =>
        // else ignore: it is a reliably delivered message that might be retried later, and it has not yet deserved
        // the dead letter status
        if (seqOpt.isEmpty) super.!(DeadLetter(m, senderOption.getOrElse(_provider.deadLetters), recipient))
      case env: OutboundEnvelope =>
        super.!(
          DeadLetter(
            unwrapSystemMessageEnvelope(env.message),
            env.sender.getOrElse(_provider.deadLetters),
            env.recipient.getOrElse(_provider.deadLetters)))
      case DeadLetter(env: OutboundEnvelope, _, _) =>
        super.!(
          DeadLetter(
            unwrapSystemMessageEnvelope(env.message),
            env.sender.getOrElse(_provider.deadLetters),
            env.recipient.getOrElse(_provider.deadLetters)))
      case _ => super.!(message)(sender)
    }

    private def unwrapSystemMessageEnvelope(msg: AnyRef): AnyRef = msg match {
      case SystemMessageEnvelope(m, _, _) => m
      case _                              => msg
    }

    @throws(classOf[java.io.ObjectStreamException])
    override protected def writeReplace(): AnyRef = DeadLetterActorRef.serialized
  }
}

/**
 * INTERNAL API
 * Depending on this class is not supported, only the [[pekko.actor.ActorRefProvider]] interface is supported.
 *
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 */
private[pekko] class RemoteActorRefProvider(
    val systemName: String,
    val settings: ActorSystem.Settings,
    val eventStream: EventStream,
    val dynamicAccess: DynamicAccess)
    extends ActorRefProvider {
  import RemoteActorRefProvider._

  val remoteSettings: RemoteSettings = new RemoteSettings(settings.config)

  private[pekko] final val hasClusterOrUseUnsafe =
    settings.HasCluster || remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster

  private val warnOnUnsafeRemote =
    !settings.HasCluster &&
    !remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster &&
    remoteSettings.WarnUnsafeWatchWithoutCluster

  override val deployer: Deployer = createDeployer

  /**
   * Factory method to make it possible to override deployer in subclass
   * Creates a new instance every time
   */
  protected def createDeployer: RemoteDeployer = new RemoteDeployer(settings, dynamicAccess)

  private[pekko] val local = new LocalActorRefProvider(
    systemName,
    settings,
    eventStream,
    dynamicAccess,
    deployer,
    Some(deadLettersPath => new RemoteDeadLetterActorRef(this, deadLettersPath, eventStream)))

  @volatile
  private var _log = local.log
  def log: LoggingAdapter = _log

  override def rootPath: ActorPath = local.rootPath
  override def deadLetters: InternalActorRef = local.deadLetters
  override def ignoreRef: ActorRef = local.ignoreRef

  // these are only available after init()
  override def rootGuardian: InternalActorRef = local.rootGuardian
  override def guardian: LocalActorRef = local.guardian
  override def systemGuardian: LocalActorRef = local.systemGuardian
  override def terminationFuture: Future[Terminated] = local.terminationFuture
  override def registerTempActor(actorRef: InternalActorRef, path: ActorPath): Unit =
    local.registerTempActor(actorRef, path)
  override def unregisterTempActor(path: ActorPath): Unit = local.unregisterTempActor(path)
  override def tempPath(): ActorPath = local.tempPath()
  override def tempPath(prefix: String): ActorPath = local.tempPath(prefix)
  override def tempContainer: VirtualPathContainer = local.tempContainer

  @volatile private var _internals: Internals = _

  def transport: RemoteTransport = _internals.transport
  def remoteDaemon: InternalActorRef = _internals.remoteDaemon

  // This actor ensures the ordering of shutdown between remoteDaemon and the transport
  @volatile private var remotingTerminator: ActorRef = _

  @volatile private var _remoteWatcher: Option[ActorRef] = None
  private[pekko] def remoteWatcher: Option[ActorRef] = _remoteWatcher

  @volatile private var remoteDeploymentWatcher: Option[ActorRef] = None

  @volatile private var actorRefResolveThreadLocalCache: ActorRefResolveThreadLocalCache = _

  def init(system: ActorSystemImpl): Unit = {
    local.init(system)

    actorRefResolveThreadLocalCache = ActorRefResolveThreadLocalCache(system)

    remotingTerminator = system.systemActorOf(
      remoteSettings.configureDispatcher(Props(classOf[RemotingTerminator], local.systemGuardian)),
      "remoting-terminator")

    if (remoteSettings.Artery.Enabled && remoteSettings.Artery.Transport == AeronUpd) {
      checkAeronOnClassPath(system)
    } else if (!remoteSettings.Artery.Enabled) {
      checkNettyOnClassPath(system)
    } // artery tcp has no dependencies

    val internals = Internals(
      remoteDaemon = {
        val d = new RemoteSystemDaemon(
          system,
          local.rootPath / "remote",
          rootGuardian,
          remotingTerminator,
          _log,
          untrustedMode = remoteSettings.untrustedMode)
        local.registerExtraNames(Map(("remote", d)))
        d
      },
      transport = if (remoteSettings.Artery.Enabled) remoteSettings.Artery.Transport match {
        case ArterySettings.AeronUpd => new ArteryAeronUdpTransport(system, this)
        case ArterySettings.Tcp      => new ArteryTcpTransport(system, this, tlsEnabled = false)
        case ArterySettings.TlsTcp   => new ArteryTcpTransport(system, this, tlsEnabled = true)
      }
      else new Remoting(system, this))
    _internals = internals
    remotingTerminator ! internals

    _log = Logging.withMarker(eventStream, getClass.getName)

    warnIfDirectUse()
    warnIfUseUnsafeWithoutCluster()

    // this enables reception of remote requests
    transport.start()

    _addressString = OptionVal.Some(_internals.transport.defaultAddress.toString)
    _remoteWatcher = createOrNone[ActorRef](createRemoteWatcher(system))
    remoteDeploymentWatcher = createOrNone[ActorRef](createRemoteDeploymentWatcher(system))
  }

  private def checkNettyOnClassPath(system: ActorSystemImpl): Unit = {
    checkClassOrThrow(
      system,
      "io.netty.channel.Channel",
      "Classic",
      "Netty",
      "https://pekko.apache.org/docs/pekko/current/remoting.html")
  }

  private def checkAeronOnClassPath(system: ActorSystemImpl): Unit = {
    val arteryLink = "https://pekko.apache.org/docs/pekko/current/remoting-artery.html"
    // using classes that are used so will fail to compile if they get removed from Aeron
    checkClassOrThrow(system, "io.aeron.driver.MediaDriver", "Artery", "Aeron driver", arteryLink)
    checkClassOrThrow(system, "io.aeron.Aeron", "Artery", "Aeron client", arteryLink)
  }

  private def checkClassOrThrow(
      system: ActorSystemImpl,
      className: String,
      remoting: String,
      libraryMissing: String,
      link: String): Unit = {
    system.dynamicAccess.getClassFor[Any](className) match {
      case Failure(_: ClassNotFoundException | _: NoClassDefFoundError) =>
        throw new IllegalStateException(
          s"$remoting remoting is enabled but $libraryMissing is not on the classpath, it must be added explicitly. See $link")
      case _ =>
    }
  }

  /** Will call the provided `func` if using Cluster or explicitly enabled unsafe remote features. */
  private def createOrNone[T](func: => T): Option[T] = if (hasClusterOrUseUnsafe) Some(func) else None

  protected def createRemoteWatcher(system: ActorSystemImpl): ActorRef = {
    import remoteSettings._
    system.systemActorOf(
      configureDispatcher(RemoteWatcher.props(remoteSettings, createRemoteWatcherFailureDetector(system))),
      "remote-watcher")
  }

  protected def createRemoteWatcherFailureDetector(system: ExtendedActorSystem): FailureDetectorRegistry[Address] = {
    def createFailureDetector(): FailureDetector =
      FailureDetectorLoader.load(
        remoteSettings.WatchFailureDetectorImplementationClass,
        remoteSettings.WatchFailureDetectorConfig,
        system)

    new DefaultFailureDetectorRegistry(() => createFailureDetector())
  }

  protected def createRemoteDeploymentWatcher(system: ActorSystemImpl): ActorRef =
    system.systemActorOf(
      remoteSettings.configureDispatcher(Props[RemoteDeploymentWatcher]()),
      "remote-deployment-watcher")

  /** Can be overridden when using RemoteActorRefProvider as a superclass rather than directly */
  protected def warnIfDirectUse(): Unit = {
    if (remoteSettings.WarnAboutDirectUse) {
      log.warning(
        "Using the 'remote' ActorRefProvider directly, which is a low-level layer. " +
        "For most use cases, the 'cluster' abstraction on top of remoting is more suitable instead.")
    }
  }

  // Log on `init` similar to `warnIfDirectUse`.
  private[pekko] def warnIfUseUnsafeWithoutCluster(): Unit =
    if (!settings.HasCluster) {
      if (remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster)
        log.info(
          "Pekko Cluster not in use - enabling unsafe features anyway because `pekko.remote.use-unsafe-remote-features-outside-cluster` has been enabled.")
      else
        log.warning(
          "Pekko Cluster not in use - Using Pekko Cluster is recommended if you need remote watch and deploy.")
    }

  protected def warnOnUnsafe(message: String): Unit =
    if (warnOnUnsafeRemote) log.warning(message)
    else log.debug(message)

  /**
   * Logs if deathwatch message is intentionally dropped. To disable
   * warnings set `pekko.remote.warn-unsafe-watch-outside-cluster` to `off`
   * or use Pekko Cluster.
   */
  private[pekko] def warnIfUnsafeDeathwatchWithoutCluster(watchee: ActorRef, watcher: ActorRef, action: String): Unit =
    warnOnUnsafe(s"Dropped remote $action: disabled for [$watcher -> $watchee]")

  /**
   * If `warnOnUnsafeRemote`, this logs a warning if `actorOf` falls back to `LocalActorRef`
   * versus creating a `RemoteActorRef`. Override to log a more granular reason if using
   * `RemoteActorRefProvider` as a superclass.
   */
  protected def warnIfNotRemoteActorRef(path: ActorPath): Unit =
    warnOnUnsafe(s"Remote deploy of [$path] is not allowed, falling back to local.")

  /** Override to add any additional checks if using `RemoteActorRefProvider` as a superclass. */
  protected def shouldCreateRemoteActorRef(@unused system: ActorSystem, @unused address: Address): Boolean = true

  def actorOf(
      system: ActorSystemImpl,
      props: Props,
      supervisor: InternalActorRef,
      path: ActorPath,
      systemService: Boolean,
      deploy: Option[Deploy],
      lookupDeploy: Boolean,
      async: Boolean): InternalActorRef =
    if (systemService) local.actorOf(system, props, supervisor, path, systemService, deploy, lookupDeploy, async)
    else {

      /*
       * This needs to deal with “mangled” paths, which are created by remote
       * deployment, also in this method. The scheme is the following:
       *
       * Whenever a remote deployment is found, create a path on that remote
       * address below “remote”, including the current system’s identification
       * as “sys@host:port” (typically; it will use whatever the remote
       * transport uses). This means that on a path up an actor tree each node
       * change introduces one layer or “remote/scheme/sys@host:port/” within the URI.
       *
       * Example:
       *
       * pekko://sys@home:1234/remote/pekko/sys@remote:6667/remote/pekko/sys@other:3333/user/a/b/c
       *
       * means that the logical parent originates from “pekko://sys@other:3333” with
       * one child (may be “a” or “b”) being deployed on “pekko://sys@remote:6667” and
       * finally either “b” or “c” being created on “pekko://sys@home:1234”, where
       * this whole thing actually resides. Thus, the logical path is
       * “/user/a/b/c” and the physical path contains all remote placement
       * information.
       *
       * Deployments are always looked up using the logical path, which is the
       * purpose of the lookupRemotes internal method.
       */

      @scala.annotation.tailrec
      def lookupRemotes(p: Iterable[String]): Option[Deploy] = {
        p.headOption match {
          case None           => None
          case Some("remote") => lookupRemotes(p.drop(3))
          case Some("user")   => deployer.lookup(p.drop(1))
          case Some(_)        => None
        }
      }

      val elems = path.elements
      val lookup =
        if (lookupDeploy)
          elems.head match {
            case "user" | "system" => deployer.lookup(elems.drop(1))
            case "remote"          => lookupRemotes(elems)
            case _                 => None
          }
        else None

      val deployment = {
        deploy.toList ::: lookup.toList match {
          case Nil => Nil
          case l   => List(l.reduce((a, b) => b.withFallback(a)))
        }
      }

      (Iterator(props.deploy) ++ deployment.iterator).reduce((a, b) => b.withFallback(a)) match {
        case d @ Deploy(_, _, _, RemoteScope(address), _, _) =>
          if (hasAddress(address)) {
            local.actorOf(system, props, supervisor, path, false, deployment.headOption, false, async)
          } else if (props.deploy.scope == LocalScope) {
            throw new ConfigurationException(
              s"${ErrorMessages.RemoteDeploymentConfigErrorPrefix} for local-only Props at [$path]")
          } else
            try {
              if (hasClusterOrUseUnsafe && shouldCreateRemoteActorRef(system, address)) {
                try {
                  // for consistency we check configuration of dispatcher and mailbox locally
                  val dispatcher = system.dispatchers.lookup(props.dispatcher)
                  system.mailboxes.getMailboxType(props, dispatcher.configurator.config)
                } catch {
                  case NonFatal(e) =>
                    throw new ConfigurationException(
                      s"configuration problem while creating [$path] with dispatcher [${props.dispatcher}] and mailbox [${props.mailbox}]",
                      e)
                }
                val localAddress = transport.localAddressForRemote(address)
                val rpath =
                  (RootActorPath(address) / "remote" / localAddress.protocol / localAddress.hostPort / path.elements)
                    .withUid(path.uid)
                new RemoteActorRef(transport, localAddress, rpath, supervisor, Some(props), Some(d),
                  remoteSettings.AcceptProtocolNames)
              } else {
                warnIfNotRemoteActorRef(path)
                local.actorOf(system, props, supervisor, path, systemService, deployment.headOption, false, async)
              }

            } catch {
              case NonFatal(e) => throw new IllegalArgumentException(s"remote deployment failed for [$path]", e)
            }
        case _ =>
          local.actorOf(system, props, supervisor, path, systemService, deployment.headOption, false, async)
      }
    }

  def rootGuardianAt(address: Address): ActorRef = {
    if (hasAddress(address)) rootGuardian
    else
      try {
        new RemoteActorRef(
          transport,
          transport.localAddressForRemote(address),
          RootActorPath(address),
          Nobody,
          props = None,
          deploy = None,
          acceptProtocolNames = remoteSettings.AcceptProtocolNames)
      } catch {
        case NonFatal(e) =>
          log.error(e, "No root guardian at [{}]", address)
          new EmptyLocalActorRef(this, RootActorPath(address), eventStream)
      }
  }

  /**
   * INTERNAL API
   * Called in deserialization of incoming remote messages where the correct local address is known.
   */
  private[pekko] def resolveActorRefWithLocalAddress(path: String, localAddress: Address): InternalActorRef = {
    path match {
      case ActorPathExtractor(address, elems) =>
        if (hasAddress(address))
          local.resolveActorRef(rootGuardian, elems)
        else
          try {
            new RemoteActorRef(
              transport,
              localAddress,
              RootActorPath(address) / elems,
              Nobody,
              props = None,
              deploy = None,
              acceptProtocolNames = remoteSettings.AcceptProtocolNames)
          } catch {
            case NonFatal(e) =>
              log.warning("Error while resolving ActorRef [{}] due to [{}]", path, e.getMessage)
              new EmptyLocalActorRef(this, RootActorPath(address) / elems, eventStream)
          }
      case _ =>
        log.debug("Resolve (deserialization) of unknown (invalid) path [{}], using deadLetters.", path)
        deadLetters
    }
  }

  def resolveActorRef(path: String): ActorRef = {
    // using thread local LRU cache, which will call internalResolveActorRef
    // if the value is not cached
    actorRefResolveThreadLocalCache match {
      case null =>
        internalResolveActorRef(path) // not initialized yet
      case c =>
        c.threadLocalCache(this).resolve(path)
    }
  }

  /**
   * INTERNAL API: This is used by the `ActorRefResolveCache` via the
   * public `resolveActorRef(path: String)`.
   */
  private[pekko] def internalResolveActorRef(path: String): ActorRef = path match {

    case p if IgnoreActorRef.isIgnoreRefPath(p) => this.ignoreRef

    case ActorPathExtractor(address, elems) =>
      if (hasAddress(address)) local.resolveActorRef(rootGuardian, elems)
      else {
        val rootPath = RootActorPath(address) / elems
        try {
          new RemoteActorRef(
            transport,
            transport.localAddressForRemote(address),
            rootPath,
            Nobody,
            props = None,
            deploy = None,
            acceptProtocolNames = remoteSettings.AcceptProtocolNames)
        } catch {
          case NonFatal(e) =>
            log.warning("Error while resolving ActorRef [{}] due to [{}]", path, e.getMessage)
            new EmptyLocalActorRef(this, rootPath, eventStream)
        }
      }

    case _ =>
      log.debug("Resolve (deserialization) of unknown (invalid) path [{}], using deadLetters.", path)
      deadLetters
  }

  def resolveActorRef(path: ActorPath): ActorRef = {
    if (hasAddress(path.address)) local.resolveActorRef(rootGuardian, path.elements)
    else
      try {
        new RemoteActorRef(
          transport,
          transport.localAddressForRemote(path.address),
          path,
          Nobody,
          props = None,
          deploy = None,
          acceptProtocolNames = remoteSettings.AcceptProtocolNames)
      } catch {
        case NonFatal(e) =>
          log.warning("Error while resolving ActorRef [{}] due to [{}]", path, e.getMessage)
          new EmptyLocalActorRef(this, path, eventStream)
      }
  }

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(ref: ActorRef, props: Props, deploy: Deploy, supervisor: ActorRef): Unit =
    remoteDeploymentWatcher match {
      case Some(watcher) =>
        log.debug("[{}] Instantiating Remote Actor [{}]", rootPath, ref.path)

        // we don’t wait for the ACK, because the remote end will process this command before any other message to the new actor
        // actorSelection can't be used here because then it is not guaranteed that the actor is created
        // before someone can send messages to it
        resolveActorRef(RootActorPath(ref.path.address) / "remote") !
        DaemonMsgCreate(props, deploy, ref.path.toSerializationFormat, supervisor)

        watcher ! RemoteDeploymentWatcher.WatchRemote(ref, supervisor)
      case None => warnIfUseUnsafeWithoutCluster()
    }

  def getExternalAddressFor(addr: Address): Option[Address] = {
    addr match {
      case _ if hasAddress(addr)           => Some(local.rootPath.address)
      case Address(_, _, Some(_), Some(_)) =>
        try Some(transport.localAddressForRemote(addr))
        catch { case NonFatal(_) => None }
      case _ => None
    }
  }

  def getDefaultAddress: Address = transport.defaultAddress

  // no need for volatile, only intended as cached value, not necessarily a singleton value
  private var serializationInformationCache: OptionVal[Serialization.Information] = OptionVal.None
  @InternalApi override private[pekko] def serializationInformation: Serialization.Information =
    serializationInformationCache match {
      case OptionVal.Some(info) => info
      case _                    =>
        if ((transport eq null) || (transport.defaultAddress eq null))
          local.serializationInformation // address not know yet, access before complete init and binding
        else {
          val info = Serialization.Information(transport.defaultAddress, transport.system)
          serializationInformationCache = OptionVal.Some(info)
          info
        }
    }

  private def hasAddress(address: Address): Boolean =
    address == local.rootPath.address || address == rootPath.address || transport.addresses(address)

  /**
   * Marks a remote system as out of sync and prevents reconnects until the quarantine timeout elapses.
   *
   * @param address Address of the remote system to be quarantined
   * @param uid UID of the remote system, if the uid is not defined it will not be a strong quarantine but
   *   the current endpoint writer will be stopped (dropping system messages) and the address will be gated
   */
  def quarantine(address: Address, uid: Option[Long], reason: String): Unit =
    transport.quarantine(address, uid, reason)

  // lazily initialized with fallback since it can depend on transport which is not initialized up front
  // worth caching since if it is used once in a system it will very likely be used many times
  @volatile private var _addressString: OptionVal[String] = OptionVal.None
  override private[pekko] def addressString: String = {
    _addressString match {
      case OptionVal.Some(addr) => addr
      case _                    =>
        // not initialized yet, fallback
        local.addressString
    }
  }
}

private[pekko] trait RemoteRef extends ActorRefScope {
  final def isLocal = false
}

/**
 * INTERNAL API
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 */
private[pekko] class RemoteActorRef private[pekko] (
    remote: RemoteTransport,
    val localAddressToUse: Address,
    val path: ActorPath,
    val getParent: InternalActorRef,
    props: Option[Props],
    deploy: Option[Deploy],
    val acceptProtocolNames: Set[String])
    extends InternalActorRef
    with RemoteRef {

  if (path.address.hasLocalScope)
    throw new IllegalArgumentException(s"Unexpected local address in RemoteActorRef [$this]")

  remote match {
    case _: ArteryTransport =>
      // detect mistakes such as using "pekko.tcp" with Artery, also handles pekko.remote.accept-protocol-names
      if (!acceptProtocolNames.contains(path.address.protocol)) {
        val expectedString = if (acceptProtocolNames.size == 1)
          "expected"
        else
          "expected one of"

        throw new IllegalArgumentException(
          s"Wrong protocol of [$path], $expectedString [${acceptProtocolNames.mkString}]")
      }
    case _ =>
  }
  @volatile private[remote] var cachedAssociation: artery.Association = null

  // used by artery to direct messages to separate specialized streams
  @volatile private[remote] var cachedSendQueueIndex: Int = -1

  @nowarn("msg=deprecated")
  def getChild(name: Iterator[String]): InternalActorRef = {
    val s = name.toStream
    s.headOption match {
      case None       => this
      case Some("..") => getParent.getChild(name)
      case _          => new RemoteActorRef(remote, localAddressToUse, path / s, Nobody, props = None, deploy = None,
          acceptProtocolNames = acceptProtocolNames)
    }
  }

  @deprecated("Use context.watch(actor) and receive Terminated(actor)", "Akka 2.2")
  override private[pekko] def isTerminated: Boolean = false

  private def handleException(message: Any, sender: ActorRef): Catcher[Unit] = {
    case e: InterruptedException =>
      remote.system.eventStream.publish(Error(e, path.toString, getClass, "interrupted during message send"))
      remote.system.deadLetters.tell(message, sender)
      Thread.currentThread.interrupt()
    case NonFatal(e) =>
      remote.system.eventStream.publish(Error(e, path.toString, getClass, "swallowing exception during message send"))
      remote.system.deadLetters.tell(message, sender)
  }

  /**
   * Determine if a watch/unwatch message must be handled by the remoteWatcher actor, or sent to this remote ref
   */
  def isWatchIntercepted(watchee: ActorRef, watcher: ActorRef): Boolean = {
    // If watchee != this then watcher should == this. This is a reverse watch, and it is not intercepted
    // If watchee == this, only the watches from remoteWatcher are sent on the wire, on behalf of other watchers
    provider.remoteWatcher.exists(remoteWatcher => watcher != remoteWatcher) && watchee == this
  }

  def sendSystemMessage(message: SystemMessage): Unit =
    try {
      // send to remote, unless watch message is intercepted by the remoteWatcher
      message match {
        case Watch(watchee, watcher) =>
          if (isWatchIntercepted(watchee, watcher))
            provider.remoteWatcher.foreach(_ ! RemoteWatcher.WatchRemote(watchee, watcher))
          else if (provider.remoteWatcher.isDefined)
            remote.send(message, OptionVal.None, this)
          else
            provider.warnIfUnsafeDeathwatchWithoutCluster(watchee, watcher, "Watch")

        // Unwatch has a different signature, need to pattern match arguments against InternalActorRef
        case Unwatch(watchee: InternalActorRef, watcher: InternalActorRef) =>
          if (isWatchIntercepted(watchee, watcher))
            provider.remoteWatcher.foreach(_ ! RemoteWatcher.UnwatchRemote(watchee, watcher))
          else if (provider.remoteWatcher.isDefined)
            remote.send(message, OptionVal.None, this)

        case _ =>
          remote.send(message, OptionVal.None, this)
      }
    } catch handleException(message, Actor.noSender)

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = {
    if (message == null) throw InvalidMessageException("Message is null")
    try remote.send(message, OptionVal(sender), this)
    catch handleException(message, sender)
  }

  override def provider: RemoteActorRefProvider = remote.provider

  def start(): Unit =
    if (props.isDefined && deploy.isDefined) remote.provider.useActorOnNode(this, props.get, deploy.get, getParent)

  def suspend(): Unit = sendSystemMessage(Suspend())

  def resume(causedByFailure: Throwable): Unit = sendSystemMessage(Resume(causedByFailure))

  def stop(): Unit = sendSystemMessage(Terminate())

  def restart(cause: Throwable): Unit = sendSystemMessage(Recreate(cause))

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef(this)
}
