/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2015-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.cluster.client.protobuf

import java.io.NotSerializableException

import scala.annotation.nowarn

import org.apache.pekko
import pekko.actor.ExtendedActorSystem
import pekko.cluster.client.ClusterReceptionist
import pekko.cluster.client.protobuf.msg.{ ClusterClientMessages => cm }
import pekko.serialization.BaseSerializer
import pekko.serialization.SerializerWithStringManifest
import pekko.util.ccompat.JavaConverters._

/**
 * INTERNAL API: Serializer of ClusterClient messages.
 */
@nowarn("msg=deprecated")
private[pekko] class ClusterClientMessageSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {
  import ClusterReceptionist.Internal._

  private val ContactsManifest = "A"
  private val GetContactsManifest = "B"
  private val HeartbeatManifest = "C"
  private val HeartbeatRspManifest = "D"
  private val ReceptionistShutdownManifest = "E"

  private val emptyByteArray = Array.empty[Byte]

  private val fromBinaryMap = collection.immutable.HashMap[String, Array[Byte] => AnyRef](
    ContactsManifest -> contactsFromBinary,
    GetContactsManifest -> { _ =>
      GetContacts
    },
    HeartbeatManifest -> { _ =>
      Heartbeat
    },
    HeartbeatRspManifest -> { _ =>
      HeartbeatRsp
    },
    ReceptionistShutdownManifest -> { _ =>
      ReceptionistShutdown
    })

  override def manifest(obj: AnyRef): String = obj match {
    case _: Contacts          => ContactsManifest
    case GetContacts          => GetContactsManifest
    case Heartbeat            => HeartbeatManifest
    case HeartbeatRsp         => HeartbeatRspManifest
    case ReceptionistShutdown => ReceptionistShutdownManifest
    case _                    =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  override def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: Contacts          => contactsToProto(m).toByteArray
    case GetContacts          => emptyByteArray
    case Heartbeat            => emptyByteArray
    case HeartbeatRsp         => emptyByteArray
    case ReceptionistShutdown => emptyByteArray
    case _                    =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(f) => f(bytes)
      case None    =>
        throw new NotSerializableException(
          s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
    }

  private def contactsToProto(m: Contacts): cm.Contacts =
    cm.Contacts.newBuilder().addAllContactPoints(m.contactPoints.asJava).build()

  private def contactsFromBinary(bytes: Array[Byte]): Contacts = {
    val m = cm.Contacts.parseFrom(bytes)
    Contacts(m.getContactPointsList.asScala.toVector)
  }

}
