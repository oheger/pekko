# Classic Remoting (Deprecated)

@@@ warning

Classic remoting has been deprecated. Please use @ref[Artery](remoting-artery.md) instead.

@@@

@@@ note

Remoting is the mechanism by which Actors on different nodes talk to each
other internally.

When building a Pekko application, you would usually not use the Remoting concepts
directly, but instead use the more high-level
@ref[Pekko Cluster](index-cluster.md) utilities or technology-agnostic protocols
such as [HTTP]($pekko.doc.dns$/docs/pekko-http/current/),
[gRPC]($pekko.doc.dns$/docs/pekko-grpc/current/) etc.


@@@

## Module info

To use Pekko Remoting, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  bomGroup=org.apache.pekko bomArtifact=pekko-bom_$scala.binary.version$ bomVersionSymbols=PekkoVersion
  symbol1=PekkoVersion
  value1="$pekko.version$"
  group=org.apache.pekko
  artifact=pekko-remote_$scala.binary.version$
  version=PekkoVersion
}

@@project-info{ projectId="remote" }

Classic remoting depends on Netty. This needs to be explicitly added as a dependency so that users
not using classic remoting do not have to have Netty on the classpath:

@@dependency[sbt,Maven,Gradle] {
  group=io.netty
  artifact=netty
  version=$netty_version$
}

## Configuration

To enable classic remoting in your Pekko project you should, at a minimum, add the following changes
to your `application.conf` file:

```
pekko {
  actor {
    # provider=remote is possible, but prefer cluster
    provider = cluster
  }
  remote.artery.enabled = false
  remote.classic {
    enabled-transports = ["pekko.remote.classic.netty.tcp"]
    netty.tcp {
      hostname = "127.0.0.1"
      port = 7355
    }
 }
}
```

As you can see in the example above there are four things you need to add to get started:

 * Change provider from `local`. We recommend using @ref:[Pekko Cluster](cluster-usage.md) over using remoting directly.
 * Disable artery remoting. Artery is the default remoting implementation in Apache Pekko.
 * Add host name - the machine you want to run the actor system on; this host
name is exactly what is passed to remote systems in order to identify this
system and consequently used for connecting back to this system if need be,
hence set it to a reachable IP address or resolvable name in case you want to
communicate across the network.
 * Add port number - the port the actor system should listen on, set to 0 to have it chosen automatically

@@@ note

The port number needs to be unique for each actor system on the same machine even if the actor
systems have different names. This is because each actor system has its own networking subsystem
listening for connections and handling messages as not to interfere with other actor systems.

@@@

The example above only illustrates the bare minimum of properties you have to add to enable remoting.
All settings are described in @ref:[Remote Configuration](#remote-configuration).

## Introduction

We recommend @ref:[Pekko Cluster](cluster-usage.md) over using remoting directly. As remoting is the
underlying module that allows for Cluster, it is still useful to understand details about it though.

For an introduction of remoting capabilities of Pekko please see @ref:[Location Transparency](general/remoting.md).

@@@ note

As explained in that chapter Pekko remoting is designed for communication in a
peer-to-peer fashion and it is not a good fit for client-server setups. In
particular Pekko Remoting does not work transparently with Network Address Translation,
Load Balancers, or in Docker containers. For symmetric communication in these situations
network and/or Pekko configuration will have to be changed as described in
[Pekko behind NAT or in a Docker container](#remote-configuration-nat).

@@@

You need to enable @ref:[serialization](serialization.md) for your actor messages.
@ref:[Serialization with Jackson](serialization-jackson.md) is a good choice in many cases and our
recommendation if you don't have other preference.

## Types of Remote Interaction

Pekko has two ways of using remoting:

 * Lookup    : used to look up an actor on a remote node with `actorSelection(path)`
 * Creation  : used to create an actor on a remote node with `actorOf(Props(...), actorName)`

In the next sections the two alternatives are described in detail.

## Looking up Remote Actors

`actorSelection(path)` will obtain an `ActorSelection` to an Actor on a remote node, e.g.:

Scala
:   ```
val selection =
  context.actorSelection("pekko.tcp://actorSystemName@10.0.0.1:7355/user/actorName")
```

Java
:   ```
ActorSelection selection =
  context.actorSelection("pekko.tcp://app@10.0.0.1:7355/user/serviceA/worker");
```

As you can see from the example above the following pattern is used to find an actor on a remote node:

```
pekko.<protocol>://<actor system name>@<hostname>:<port>/<actor path>
```

Once you obtained a selection to the actor you can interact with it in the same way you would with a local actor, e.g.:

Scala
:   ```
selection ! "Pretty awesome feature"
```

Java
:   ```
selection.tell("Pretty awesome feature", getSelf());
```

To acquire an `ActorRef` for an `ActorSelection` you need to
send a message to the selection and use the `sender` reference of the reply from
the actor. There is a built-in `Identify` message that all Actors will understand
and automatically reply to with a `ActorIdentity` message containing the
`ActorRef`. This can also be done with the `resolveOne` method of
the `ActorSelection`, which returns a @scala[`Future`]@java[`CompletionStage`] of the matching
`ActorRef`.

@@@ note

For more details on how actor addresses and paths are formed and used, please refer to @ref:[Actor References, Paths and Addresses](general/addressing.md).

@@@

@@@ note

Message sends to actors that are actually in the sending actor system do not
get delivered via the remote actor ref provider. They're delivered directly,
by the local actor ref provider.

Aside from providing better performance, this also means that if the hostname
you configure remoting to listen as cannot actually be resolved from within
the very same actor system, such messages will (perhaps counterintuitively)
be delivered just fine.

@@@

## Creating Actors Remotely

If you want to use the creation functionality in Pekko remoting you have to further amend the
`application.conf` file in the following way (only showing deployment section):

```
pekko {
  actor {
    deployment {
      /sampleActor {
        remote = "pekko.tcp://sampleActorSystem@127.0.0.1:7356"
      }
    }
  }
}
```

The configuration above instructs Pekko to react when an actor with path `/sampleActor` is created, i.e.
using @scala[`system.actorOf(Props(...), "sampleActor")`]@java[`system.actorOf(new Props(...), "sampleActor")`]. This specific actor will not be directly instantiated,
but instead the remote daemon of the remote system will be asked to create the actor,
which in this sample corresponds to `sampleActorSystem@127.0.0.1:7356`.

Once you have configured the properties above you would do the following in code:

Scala
:   @@snip [RemoteDeploymentDocSpec.scala](/docs/src/test/scala/docs/remoting/RemoteDeploymentDocSpec.scala) { #sample-actor }

Java
:   @@snip [RemoteDeploymentDocTest.java](/docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #sample-actor }

The actor class `SampleActor` has to be available to the runtimes using it, i.e. the classloader of the
actor systems has to have a JAR containing the class.

When using remote deployment of actors you must ensure that all parameters of the `Props` can
be @ref:[serialized](serialization.md).

@@@ note

In order to ensure serializability of `Props` when passing constructor
arguments to the actor being created, do not make the factory @scala[an]@java[a non-static] inner class:
this will inherently capture a reference to its enclosing object, which in
most cases is not serializable. It is best to @scala[create a factory method in the
companion object of the actor’s class]@java[make a static
inner class which implements `Creator<T extends Actor>`].

Serializability of all Props can be tested by setting the configuration item
`pekko.actor.serialize-creators=on`. Only Props whose `deploy` has
`LocalScope` are exempt from this check.

@@@

@@@ note

You can use asterisks as wildcard matches for the actor path sections, so you could specify:
`/*/sampleActor` and that would match all `sampleActor` on that level in the hierarchy.
You can also use wildcard in the last position to match all actors at a certain level:
`/someParent/*`. Non-wildcard matches always have higher priority to match than wildcards, so:
`/foo/bar` is considered **more specific** than `/foo/*` and only the highest priority match is used.
Please note that it **cannot** be used to partially match section, like this: `/foo*/bar`, `/f*o/bar` etc.

@@@

### Programmatic Remote Deployment

To allow dynamically deployed systems, it is also possible to include
deployment configuration in the `Props` which are used to create an
actor: this information is the equivalent of a deployment section from the
configuration file, and if both are given, the external configuration takes
precedence.

With these imports:

Scala
:   @@snip [RemoteDeploymentDocSpec.scala](/docs/src/test/scala/docs/remoting/RemoteDeploymentDocSpec.scala) { #import }

Java
:   @@snip [RemoteDeploymentDocTest.java](/docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #import }

and a remote address like this:

Scala
:   @@snip [RemoteDeploymentDocSpec.scala](/docs/src/test/scala/docs/remoting/RemoteDeploymentDocSpec.scala) { #make-address }

Java
:   @@snip [RemoteDeploymentDocTest.java](/docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #make-address }

you can advise the system to create a child on that remote node like so:

Scala
:   @@snip [RemoteDeploymentDocSpec.scala](/docs/src/test/scala/docs/remoting/RemoteDeploymentDocSpec.scala) { #deploy }

Java
:   @@snip [RemoteDeploymentDocTest.java](/docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #deploy }

### Remote deployment allow list

As remote deployment can potentially be abused by both users and even attackers an allow list feature
is available to guard the ActorSystem from deploying unexpected actors. Please note that remote deployment
is *not* remote code loading, the Actors class to be deployed onto a remote system needs to be present on that
remote system. This still however may pose a security risk, and one may want to restrict remote deployment to
only a specific set of known actors by enabling the allow list feature.

To enable remote deployment allow list set the `pekko.remote.deployment.enable-allow-list` value to `on`.
The list of allowed classes has to be configured on the "remote" system, in other words on the system onto which
others will be attempting to remote deploy Actors. That system, locally, knows best which Actors it should or
should not allow others to remote deploy onto it. The full settings section may for example look like this:

@@snip [RemoteDeploymentAllowListSpec.scala](/remote/src/test/scala/org/apache/pekko/remote/classic/RemoteDeploymentAllowListSpec.scala) { #allow-list-config }

Actor classes not included in the allow list will not be allowed to be remote deployed onto this system.

## Lifecycle and Failure Recovery Model

![association_lifecycle.png](./images/association_lifecycle.png)

Each link with a remote system can be in one of the four states as illustrated above. Before any communication
happens with a remote system at a given `Address` the state of the association is `Idle`. The first time a message
is attempted to be sent to the remote system or an inbound connection is accepted the state of the link transitions to
`Active` denoting that the two systems has messages to send or receive and no failures were encountered so far.
When a communication failure happens and the connection is lost between the two systems the link becomes `Gated`.

In this state the system will not attempt to connect to the remote host and all outbound messages will be dropped. The time
while the link is in the `Gated` state is controlled by the setting `pekko.remote.retry-gate-closed-for`:
after this time elapses the link state transitions to `Idle` again. `Gate` is one-sided in the
sense that whenever a successful *inbound* connection is accepted from a remote system during `Gate` it automatically
transitions to `Active` and communication resumes immediately.

In the face of communication failures that are unrecoverable because the state of the participating systems are inconsistent,
the remote system becomes `Quarantined`. Unlike `Gate`, quarantining is permanent and lasts until one of the systems
is restarted. After a restart communication can be resumed again and the link can become `Active` again.

## Watching Remote Actors

Watching a remote actor is not different than watching a local actor, as described in
@ref:[Lifecycle Monitoring aka DeathWatch](actors.md#deathwatch).

### Failure Detector

Please see:

* @ref:[Phi Accrual Failure Detector](typed/failure-detector.md) implementation for details
* @ref:[Using the Failure Detector](#using-the-failure-detector) below for usage 

### Using the Failure Detector
 
Remoting uses the `org.apache.pekko.remote.PhiAccrualFailureDetector` failure detector by default, or you can provide your by
implementing the `org.apache.pekko.remote.FailureDetector` and configuring it:

```
pekko.remote.watch-failure-detector.implementation-class = "com.example.CustomFailureDetector"
``` 
 
In the @ref:[Remote Configuration](#remote-configuration) you may want to adjust these
depending on you environment:

* When a *phi* value is considered to be a failure `pekko.remote.watch-failure-detector.threshold`
* Margin of error for sudden abnormalities `pekko.remote.watch-failure-detector.acceptable-heartbeat-pause`  
 
## Serialization

You need to enable @ref:[serialization](serialization.md) for your actor messages.
@ref:[Serialization with Jackson](serialization-jackson.md) is a good choice in many cases and our
recommendation if you don't have other preference.

## Routers with Remote Destinations

It is absolutely feasible to combine remoting with @ref:[Routing](routing.md).

A pool of remote deployed routees can be configured as:

@@snip [RouterDocSpec.scala](/docs/src/test/scala/docs/routing/RouterDocSpec.scala) { #config-remote-round-robin-pool }

This configuration setting will clone the actor defined in the `Props` of the `remotePool` 10
times and deploy it evenly distributed across the two given target nodes.

When using a pool of remote deployed routees you must ensure that all parameters of the `Props` can
be @ref:[serialized](serialization.md).

A group of remote actors can be configured as:

@@snip [RouterDocSpec.scala](/docs/src/test/scala/docs/routing/RouterDocSpec.scala) { #config-remote-round-robin-group }

This configuration setting will send messages to the defined remote actor paths.
It requires that you create the destination actors on the remote nodes with matching paths.
That is not done by the router.

### Remote Events

It is possible to listen to events that occur in Pekko Remote, and to subscribe/unsubscribe to these events
you register as listener to the below described types in on the `ActorSystem.eventStream`.

@@@ note

To subscribe to any remote event, subscribe to
`RemotingLifecycleEvent`.  To subscribe to events related only to
the lifecycle of associations, subscribe to
`org.apache.pekko.remote.AssociationEvent`.

@@@

@@@ note

The use of term "Association" instead of "Connection" reflects that the
remoting subsystem may use connectionless transports, but an association
similar to transport layer connections is maintained between endpoints by
the Pekko protocol.

@@@

By default an event listener is registered which logs all of the events
described below. This default was chosen to help setting up a system, but it is
quite common to switch this logging off once that phase of the project is
finished.

@@@ note

In order to disable the logging, set
`pekko.remote.classic.log-remote-lifecycle-events = off` in your
`application.conf`.

@@@

To be notified when an association is over ("disconnected") listen to `DisassociatedEvent` which
holds the direction of the association (inbound or outbound) and the addresses of the involved parties.

To be notified  when an association is successfully established ("connected") listen to `AssociatedEvent` which
holds the direction of the association (inbound or outbound) and the addresses of the involved parties.

To intercept errors directly related to associations, listen to `AssociationErrorEvent` which
holds the direction of the association (inbound or outbound), the addresses of the involved parties and the
`Throwable` cause.

To be notified  when the remoting subsystem is ready to accept associations, listen to `RemotingListenEvent` which
contains the addresses the remoting listens on.

To be notified when the current system is quarantined by the remote system, listen to `ThisActorSystemQuarantinedEvent`,
which includes the addresses of local and remote ActorSystems.

To be notified  when the remoting subsystem has been shut down, listen to `RemotingShutdownEvent`.

To intercept generic remoting related errors, listen to `RemotingErrorEvent` which holds the `Throwable` cause.

## Remote Security

An `ActorSystem` should not be exposed via Pekko Remote over plain TCP to an untrusted network (e.g. Internet).
It should be protected by network security, such as a firewall. If that is not considered as enough protection
[TLS with mutual authentication](#remote-tls)  should be enabled.

Best practice is that Pekko remoting nodes should only be accessible from the adjacent network. Note that if TLS is
enabled with mutual authentication there is still a risk that an attacker can gain access to a valid certificate by
compromising any node with certificates issued by the same internal PKI tree.

By default, @ref[Java serialization](serialization.md#java-serialization) is disabled in Pekko.
That is also security best-practice because of its multiple
[known attack surfaces](https://community.microfocus.com/cyberres/fortify/f/fortify-discussions/317555/the-perils-of-java-deserialization).

<a id="remote-tls"></a>
### Configuring SSL/TLS for Pekko Remoting

SSL can be used as the remote transport by adding `pekko.remote.classic.netty.ssl` to the `enabled-transport` configuration section.
An example of setting up the default Netty based SSL driver as default:

```
pekko {
  remote.classic {
    enabled-transports = [pekko.remote.classic.netty.ssl]
  }
}
```

Next the actual SSL/TLS parameters have to be configured:

```
pekko {
  remote.classic {
    netty.ssl {
      hostname = "127.0.0.1"
      port = "3553"

      security {
        key-store = "/example/path/to/mykeystore.jks"
        trust-store = "/example/path/to/mytruststore.jks"

        key-store-password = ${SSL_KEY_STORE_PASSWORD}
        key-password = ${SSL_KEY_PASSWORD}
        trust-store-password = ${SSL_TRUST_STORE_PASSWORD}

        protocol = "TLSv1.3"

        enabled-algorithms = [TLS_AES_256_GCM_SHA384]
      }
    }
  }
}
```

Always use [substitution from environment variables](https://github.com/lightbend/config#optional-system-or-env-variable-overrides)
for passwords. Don't define real passwords in config files.

According to [RFC 7525](https://www.rfc-editor.org/rfc/rfc7525.html) the recommended algorithms to use with TLS 1.2 (as of writing this document) are:

 * TLS_DHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_DHE_RSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384

You should always check the latest information about security and algorithm recommendations though before you configure your system.

Since a Pekko remoting is inherently @ref:[peer-to-peer](general/remoting.md#symmetric-communication) both the key-store as well as trust-store
need to be configured on each remoting node participating in the cluster.

The official [Java Secure Socket Extension documentation](https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html)
as well as the [Oracle documentation on creating KeyStore and TrustStores](https://docs.oracle.com/cd/E19509-01/820-3503/6nf1il6er/index.html)
are both great resources to research when setting up security on the JVM. Please consult those resources when troubleshooting
and configuring SSL.

Mutual authentication between TLS peers is enabled by default.

Mutual authentication means that the passive side (the TLS server side) of a connection will also request and verify
a certificate from the connecting peer. Without this mode only the client side is requesting and verifying certificates.
While Pekko is a peer-to-peer technology, each connection between nodes starts out from one side (the "client") towards
the other (the "server").

Note that if TLS is enabled with mutual authentication there is still a risk that an attacker can gain access to a valid certificate
by compromising any node with certificates issued by the same internal PKI tree.

See also a description of the settings in the @ref:[Remote Configuration](remoting.md#remote-configuration) section.

@@@ note

When using SHA1PRNG on Linux it's recommended specify `-Djava.security.egd=file:/dev/urandom` as argument
to the JVM to prevent blocking. It is NOT as secure because it reuses the seed.

@@@

### Untrusted Mode

As soon as an actor system can connect to another remotely, it may in principle
send any possible message to any actor contained within that remote system. One
example may be sending a `PoisonPill` to the system guardian, shutting
that system down. This is not always desired, and it can be disabled with the
following setting:

```
pekko.remote.classic.untrusted-mode = on
```

This disallows sending of system messages (actor life-cycle commands,
DeathWatch, etc.) and any message extending `PossiblyHarmful` to the
system on which this flag is set. Should a client send them nonetheless they
are dropped and logged (at DEBUG level in order to reduce the possibilities for
a denial of service attack). `PossiblyHarmful` covers the predefined
messages like `PoisonPill` and `Kill`, but it can also be added
as a marker trait to user-defined messages.

@@@ warning

Untrusted mode does not give full protection against attacks by itself.
It makes it slightly harder to perform malicious or unintended actions but
it should be noted that @ref:[Java serialization](serialization.md#java-serialization)
should still not be enabled.
Additional protection can be achieved when running in an untrusted network by
network security (e.g. firewalls) and/or enabling @ref:[TLS with mutual authentication](#remote-tls).

@@@

Messages sent with actor selection are by default discarded in untrusted mode, but
permission to receive actor selection messages can be granted to specific actors
defined in configuration:

```
pekko.remote.classic.trusted-selection-paths = ["/user/receptionist", "/user/namingService"]
```

The actual message must still not be of type `PossiblyHarmful`.

In summary, the following operations are ignored by a system configured in
untrusted mode when incoming via the remoting layer:

 * remote deployment (which also means no remote supervision)
 * remote DeathWatch
 * `system.stop()`, `PoisonPill`, `Kill`
 * sending any message which extends from the `PossiblyHarmful` marker
interface, which includes `Terminated`
 * messages sent with actor selection, unless destination defined in `trusted-selection-paths`.

@@@ note

Enabling the untrusted mode does not remove the capability of the client to
freely choose the target of its message sends, which means that messages not
prohibited by the above rules can be sent to any actor in the remote system.
It is good practice for a client-facing system to only contain a well-defined
set of entry point actors, which then forward requests (possibly after
performing validation) to another actor system containing the actual worker
actors. If messaging between these two server-side systems is done using
local `ActorRef` (they can be exchanged safely between actor systems
within the same JVM), you can restrict the messages on this interface by
marking them `PossiblyHarmful` so that a client cannot forge them.

@@@

## Remote Configuration

There are lots of configuration properties that are related to remoting in Pekko. We refer to the
@ref:[reference configuration](general/configuration-reference.md#config-pekko-remote) for more information.

@@@ note

Setting properties like the listening IP and port number programmatically is
best done by using something like the following:

@@snip [RemoteDeploymentDocTest.java](/docs/src/test/java/jdocs/remoting/RemoteDeploymentDocTest.java) { #programmatic }

@@@

<a id="remote-configuration-nat"></a>
### Pekko behind NAT or in a Docker container

In setups involving Network Address Translation (NAT), Load Balancers or Docker
containers the hostname and port pair that Pekko binds to will be different than the "logical"
host name and port pair that is used to connect to the system from the outside. This requires
special configuration that sets both the logical and the bind pairs for remoting.

```
pekko.remote.classic.netty.tcp {
      hostname = my.domain.com      # external (logical) hostname
      port = 8000                   # external (logical) port

      bind-hostname = local.address # internal (bind) hostname
      bind-port = 7355              # internal (bind) port
}
```

Keep in mind that local.address will most likely be in one of private network ranges:

 * *10.0.0.0 - 10.255.255.255* (network class A)
 * *172.16.0.0 - 172.31.255.255* (network class B)
 * *192.168.0.0 - 192.168.255.255* (network class C)

For further details see [RFC 1597](https://www.rfc-editor.org/rfc/rfc1597.html) and [RFC 1918](https://www.rfc-editor.org/rfc/rfc1918.html).
