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

package org.apache.pekko.actor

import org.apache.pekko.testkit._

object HotSwapSpec {
  abstract class Becomer extends Actor {}
}

class HotSwapSpec extends PekkoSpec with ImplicitSender {
  import HotSwapSpec.Becomer

  "An Actor" must {
    "be able to become in its constructor" in {
      val a = system.actorOf(Props(new Becomer {
        context.become { case always => sender() ! always }
        def receive = { case _ => sender() ! "FAILURE" }
      }))
      a ! "pigdog"
      expectMsg("pigdog")
    }

    "be able to become multiple times in its constructor" in {
      val a = system.actorOf(Props(new Becomer {
        for (i <- 1 to 4) context.become { case always => sender() ! s"$i:$always" }
        def receive = { case _ => sender() ! "FAILURE" }
      }))
      a ! "pigdog"
      expectMsg("4:pigdog")
    }

    "be able to become with stacking in its constructor" in {
      val a = system.actorOf(Props(new Becomer {
        context.become({ case always => sender() ! "pigdog:" + always; context.unbecome() }, false)
        def receive = { case always => sender() ! "badass:" + always }
      }))
      a ! "pigdog"
      expectMsg("pigdog:pigdog")
      a ! "badass"
      expectMsg("badass:badass")
    }

    "be able to become, with stacking, multiple times in its constructor" in {
      val a = system.actorOf(Props(new Becomer {
        for (i <- 1 to 4) context.become({ case always => sender() ! s"$i:$always"; context.unbecome() }, false)
        def receive = { case _ => sender() ! "FAILURE" }
      }))
      a ! "pigdog"
      a ! "pigdog"
      a ! "pigdog"
      a ! "pigdog"
      expectMsg("4:pigdog")
      expectMsg("3:pigdog")
      expectMsg("2:pigdog")
      expectMsg("1:pigdog")
    }

    "be able to hotswap its behavior with become(..)" in {
      val a = system.actorOf(Props(new Actor {
        def receive = {
          case "init" => sender() ! "init"
          case "swap" => context.become { case x: String => context.sender() ! x }
        }
      }))

      a ! "init"
      expectMsg("init")
      a ! "swap"
      a ! "swapped"
      expectMsg("swapped")
    }

    "be able to revert hotswap its behavior with unbecome" in {
      val a = system.actorOf(Props(new Actor {
        def receive = {
          case "init" => sender() ! "init"
          case "swap" =>
            context.become {
              case "swapped" => sender() ! "swapped"
              case "revert"  => context.unbecome()
            }
        }
      }))

      a ! "init"
      expectMsg("init")
      a ! "swap"

      a ! "swapped"
      expectMsg("swapped")

      a ! "revert"
      a ! "init"
      expectMsg("init")
    }

    "revert to initial state on restart" in {

      val a = system.actorOf(Props(new Actor {
        def receive = {
          case "state" => sender() ! "0"
          case "swap"  =>
            context.become {
              case "state"   => sender() ! "1"
              case "swapped" => sender() ! "swapped"
              case "crash"   => throw new Exception("Crash (expected)!")
            }
            sender() ! "swapped"
        }
      }))
      a ! "state"
      expectMsg("0")
      a ! "swap"
      expectMsg("swapped")
      a ! "state"
      expectMsg("1")
      EventFilter[Exception](message = "Crash (expected)!", occurrences = 1).intercept { a ! "crash" }
      a ! "state"
      expectMsg("0")
    }
  }
}
