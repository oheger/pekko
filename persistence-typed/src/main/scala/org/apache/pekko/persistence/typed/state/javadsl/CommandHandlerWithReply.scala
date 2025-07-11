/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.typed.state.javadsl

import java.util.Objects
import java.util.function.BiFunction
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.function.{ Function => JFunction }

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.persistence.typed.state.internal._
import pekko.util.OptionVal
import pekko.util.FunctionConverters._

/* Note that this is a copy of CommandHandler.scala to support ReplyEffect
 * s/Effect/ReplyEffect/
 * s/CommandHandler/CommandHandlerWithReply/
 * s/CommandHandlerBuilder/CommandHandlerWithReplyBuilder/
 * s/CommandHandlerBuilderByState/CommandHandlerWithReplyBuilderByState/
 * s/DurableStateBehavior/DurableStateBehaviorWithEnforcedReplies/
 */

/**
 * FunctionalInterface for reacting on commands
 *
 * Used with [[CommandHandlerWithReplyBuilder]] to setup the behavior of a [[DurableStateBehaviorWithEnforcedReplies]]
 */
@FunctionalInterface
trait CommandHandlerWithReply[Command, State] extends CommandHandler[Command, State] {
  def apply(state: State, command: Command): ReplyEffect[State]
}

object CommandHandlerWithReplyBuilder {
  def builder[Command, State](): CommandHandlerWithReplyBuilder[Command, State] =
    new CommandHandlerWithReplyBuilder[Command, State]
}

final class CommandHandlerWithReplyBuilder[Command, State]() {

  private var builders: List[CommandHandlerWithReplyBuilderByState[Command, State, State]] = Nil

  /**
   * Use this method to define command handlers that are selected when the passed predicate holds true.
   *
   * Note: command handlers are matched in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   *
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`
   *
   * @return A new, mutable, CommandHandlerWithReplyBuilderByState
   */
  def forState(statePredicate: Predicate[State]): CommandHandlerWithReplyBuilderByState[Command, State, State] = {
    val builder = CommandHandlerWithReplyBuilderByState.builder[Command, State](statePredicate)
    builders = builder :: builders
    builder
  }

  /**
   * Use this method to define command handlers that are selected when the passed predicate holds true
   * for a given subtype of your model. Useful when the model is defined as class hierarchy.
   *
   * Note: command handlers are matched in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   *
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`
   *
   * @return A new, mutable, CommandHandlerWithReplyBuilderByState
   */
  def forState[S <: State](
      stateClass: Class[S],
      statePredicate: Predicate[S]): CommandHandlerWithReplyBuilderByState[Command, S, State] = {
    val builder = new CommandHandlerWithReplyBuilderByState[Command, S, State](stateClass, statePredicate)
    builders = builder.asInstanceOf[CommandHandlerWithReplyBuilderByState[Command, State, State]] :: builders
    builder
  }

  /**
   * Use this method to define command handlers for a given subtype of your model. Useful when the model is defined as class hierarchy.
   *
   * Note: command handlers are matched in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   *
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`.
   * @return A new, mutable, CommandHandlerWithReplyBuilderByState
   */
  def forStateType[S <: State](stateClass: Class[S]): CommandHandlerWithReplyBuilderByState[Command, S, State] = {
    val builder = CommandHandlerWithReplyBuilderByState.builder[Command, S, State](stateClass)
    builders = builder.asInstanceOf[CommandHandlerWithReplyBuilderByState[Command, State, State]] :: builders
    builder
  }

  /**
   * The handlers defined by this builder are used when the state is `null`.
   * This variant is particular useful when the empty state of your model is defined as `null`.
   *
   * Note: command handlers are matched in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   *
   * @return A new, mutable, CommandHandlerWithReplyBuilderByState
   */
  def forNullState(): CommandHandlerWithReplyBuilderByState[Command, State, State] = {
    val predicate: Predicate[State] = ((s: State) => Objects.isNull(s)).asJava
    val builder = CommandHandlerWithReplyBuilderByState.builder[Command, State](predicate)
    builders = builder :: builders
    builder
  }

  /**
   * The handlers defined by this builder are used for any not `null` state.
   *
   * Note: command handlers are matched in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   *
   * @return A new, mutable, CommandHandlerWithReplyBuilderByState
   */
  def forNonNullState(): CommandHandlerWithReplyBuilderByState[Command, State, State] = {
    val predicate: Predicate[State] = ((s: State) => Objects.nonNull(s)).asJava
    val builder = CommandHandlerWithReplyBuilderByState.builder[Command, State](predicate)
    builders = builder :: builders
    builder
  }

  /**
   * The handlers defined by this builder are used for any state.
   * This variant is particular useful for models that have a single type (ie: no class hierarchy).
   *
   * Note: command handlers are matched in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   * Extra care should be taken when using [[forAnyState]] as it will match any state. Any command handler define after it will never be reached.
   *
   * @return A new, mutable, CommandHandlerWithReplyBuilderByState
   */
  def forAnyState(): CommandHandlerWithReplyBuilderByState[Command, State, State] = {
    val predicate: Predicate[State] = ((_: State) => true).asJava
    val builder = CommandHandlerWithReplyBuilderByState.builder[Command, State](predicate)
    builders = builder :: builders
    builder
  }

  def build(): CommandHandlerWithReply[Command, State] = {

    val combined =
      builders.reverse match {
        case head :: Nil  => head
        case head :: tail =>
          tail.foldLeft(head) { (acc, builder) =>
            acc.orElse(builder)
          }
        case Nil => throw new IllegalStateException("No matchers defined")
      }

    combined.build()
  }

}

object CommandHandlerWithReplyBuilderByState {

  private val _trueStatePredicate: Predicate[Any] = new Predicate[Any] {
    override def test(t: Any): Boolean = true
  }

  private def trueStatePredicate[S]: Predicate[S] = _trueStatePredicate.asInstanceOf[Predicate[S]]

  /**
   * @param stateClass The handlers defined by this builder are used when the state is an instance of the `stateClass`
   * @return A new, mutable, CommandHandlerWithReplyBuilderByState
   */
  def builder[Command, S <: State, State](
      stateClass: Class[S]): CommandHandlerWithReplyBuilderByState[Command, S, State] =
    new CommandHandlerWithReplyBuilderByState(stateClass, statePredicate = trueStatePredicate)

  /**
   * @param statePredicate The handlers defined by this builder are used when the `statePredicate` is `true`,
   *                       useful for example when state type is an Optional
   * @return A new, mutable, CommandHandlerWithReplyBuilderByState
   */
  def builder[Command, State](
      statePredicate: Predicate[State]): CommandHandlerWithReplyBuilderByState[Command, State, State] =
    new CommandHandlerWithReplyBuilderByState(classOf[Any].asInstanceOf[Class[State]], statePredicate)

  /**
   * INTERNAL API
   */
  @InternalApi private final case class CommandHandlerCase[Command, State](
      commandPredicate: Command => Boolean,
      statePredicate: State => Boolean,
      handler: BiFunction[State, Command, ReplyEffect[State]])
}

final class CommandHandlerWithReplyBuilderByState[Command, S <: State, State] @InternalApi private[persistence] (
    private val stateClass: Class[S],
    private val statePredicate: Predicate[S]) {

  import CommandHandlerWithReplyBuilderByState.CommandHandlerCase

  private var cases: List[CommandHandlerCase[Command, State]] = Nil

  private def addCase(predicate: Command => Boolean, handler: BiFunction[S, Command, ReplyEffect[State]]): Unit = {
    cases = CommandHandlerCase[Command, State](
      commandPredicate = predicate,
      statePredicate = state =>
        if (state == null) statePredicate.test(state.asInstanceOf[S])
        else
          statePredicate.test(state.asInstanceOf[S]) && stateClass.isAssignableFrom(state.getClass),
      handler.asInstanceOf[BiFunction[State, Command, ReplyEffect[State]]]) :: cases
  }

  /**
   * Matches any command which the given `predicate` returns true for.
   *
   * Note: command handlers are selected in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   */
  def onCommand(
      predicate: Predicate[Command],
      handler: BiFunction[S, Command, ReplyEffect[State]]): CommandHandlerWithReplyBuilderByState[Command, S, State] = {
    addCase(cmd => predicate.test(cmd), handler)
    this
  }

  /**
   * Matches any command which the given `predicate` returns true for.
   *
   * Use this when the `State` is not needed in the `handler`, otherwise there is an overloaded method that pass
   * the state in a `BiFunction`.
   *
   * Note: command handlers are selected in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   */
  def onCommand(
      predicate: Predicate[Command],
      handler: JFunction[Command, ReplyEffect[State]]): CommandHandlerWithReplyBuilderByState[Command, S, State] = {
    addCase(cmd => predicate.test(cmd),
      new BiFunction[S, Command, ReplyEffect[State]] {
        override def apply(state: S, cmd: Command): ReplyEffect[State] = handler(cmd)
      })
    this
  }

  /**
   * Matches commands that are of the given `commandClass` or subclass thereof
   *
   * Note: command handlers are selected in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   */
  def onCommand[C <: Command](
      commandClass: Class[C],
      handler: BiFunction[S, C, ReplyEffect[State]]): CommandHandlerWithReplyBuilderByState[Command, S, State] = {
    addCase(
      cmd => commandClass.isAssignableFrom(cmd.getClass),
      handler.asInstanceOf[BiFunction[S, Command, ReplyEffect[State]]])
    this
  }

  /**
   * Matches commands that are of the given `commandClass` or subclass thereof.
   *
   * Use this when the `State` is not needed in the `handler`, otherwise there is an overloaded method that pass
   * the state in a `BiFunction`.
   *
   * Note: command handlers are selected in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   */
  def onCommand[C <: Command](
      commandClass: Class[C],
      handler: JFunction[C, ReplyEffect[State]]): CommandHandlerWithReplyBuilderByState[Command, S, State] = {
    onCommand[C](commandClass,
      new BiFunction[S, C, ReplyEffect[State]] {
        override def apply(state: S, cmd: C): ReplyEffect[State] = handler(cmd)
      })
  }

  /**
   * Matches commands that are of the given `commandClass` or subclass thereof.
   *
   * Use this when you just need to initialize the `State` without using any data from the command.
   *
   * Note: command handlers are selected in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   */
  def onCommand[C <: Command](
      commandClass: Class[C],
      handler: Supplier[ReplyEffect[State]]): CommandHandlerWithReplyBuilderByState[Command, S, State] = {
    onCommand[C](commandClass,
      new BiFunction[S, C, ReplyEffect[State]] {
        override def apply(state: S, cmd: C): ReplyEffect[State] = handler.get()
      })
  }

  /**
   * Matches any command.
   *
   * Use this to declare a command handler that will match any command. This is particular useful when encoding
   * a finite state machine in which the final state is not supposed to handle any new command.
   *
   * Note: command handlers are selected in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   *
   * Extra care should be taken when using [[onAnyCommand]] as it will match any command.
   * This method builds and returns the command handler since this will not let through any states to subsequent match statements.
   *
   * @return A CommandHandlerWithReply from the appended states.
   */
  def onAnyCommand(handler: BiFunction[S, Command, ReplyEffect[State]]): CommandHandlerWithReply[Command, State] = {
    addCase(_ => true, handler)
    build()
  }

  /**
   * Matches any command.
   *
   * Use this to declare a command handler that will match any command. This is particular useful when encoding
   * a finite state machine in which the final state is not supposed to handle any new command.
   *
   * Use this when you just need to return an [[ReplyEffect]] without using any data from the state.
   *
   * Note: command handlers are selected in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   *
   * Extra care should be taken when using [[onAnyCommand]] as it will match any command.
   * This method builds and returns the command handler since this will not let through any states to subsequent match statements.
   *
   * @return A CommandHandlerWithReply from the appended states.
   */
  def onAnyCommand(handler: JFunction[Command, ReplyEffect[State]]): CommandHandlerWithReply[Command, State] = {
    addCase(_ => true,
      new BiFunction[S, Command, ReplyEffect[State]] {
        override def apply(state: S, cmd: Command): ReplyEffect[State] = handler(cmd)
      })
    build()
  }

  /**
   * Matches any command.
   *
   * Use this to declare a command handler that will match any command. This is particular useful when encoding
   * a finite state machine in which the final state is not supposed to handle any new command.
   *
   * Use this when you just need to return an [[ReplyEffect]] without using any data from the command or from the state.
   *
   * Note: command handlers are selected in the order they are added. Once a matching is found, it's selected for handling the command
   * and no further lookup is done. Therefore you must make sure that their matching conditions don't overlap,
   * otherwise you risk to 'shadow' part of your command handlers.
   *
   * Extra care should be taken when using [[onAnyCommand]] as it will match any command.
   * This method builds and returns the command handler since this will not let through any states to subsequent match statements.
   *
   * @return A CommandHandlerWithReply from the appended states.
   */
  def onAnyCommand(handler: Supplier[ReplyEffect[State]]): CommandHandlerWithReply[Command, State] = {
    addCase(_ => true,
      new BiFunction[S, Command, ReplyEffect[State]] {
        override def apply(state: S, cmd: Command): ReplyEffect[State] = handler.get()
      })
    build()
  }

  /**
   * Compose this builder with another builder. The handlers in this builder will be tried first followed
   * by the handlers in `other`.
   */
  def orElse[S2 <: State](other: CommandHandlerWithReplyBuilderByState[Command, S2, State])
      : CommandHandlerWithReplyBuilderByState[Command, S2, State] = {
    val newBuilder =
      new CommandHandlerWithReplyBuilderByState[Command, S2, State](other.stateClass, other.statePredicate)
    // problem with overloaded constructor with `cases` as parameter
    newBuilder.cases = other.cases ::: cases
    newBuilder
  }

  /**
   * Builds and returns a handler from the appended states. The returned [[CommandHandlerWithReply]] will throw a [[scala.MatchError]]
   * if applied to a command that has no defined case.
   */
  def build(): CommandHandlerWithReply[Command, State] = {
    val builtCases = cases.reverse.toArray

    new CommandHandlerWithReply[Command, State] {
      override def apply(state: State, command: Command): ReplyEffect[State] = {
        var idx = 0
        var effect: OptionVal[ReplyEffect[State]] = OptionVal.None

        while (idx < builtCases.length && effect.isEmpty) {
          val curr = builtCases(idx)
          if (curr.statePredicate(state) && curr.commandPredicate(command)) {
            val x: ReplyEffect[State] = curr.handler.apply(state, command)
            effect = OptionVal.Some(x)
          }
          idx += 1
        }

        effect match {
          case OptionVal.Some(e) => e.asInstanceOf[EffectImpl[State]]
          case _                 =>
            throw new MatchError(s"No match found for command of type [${command.getClass.getName}]")
        }
      }
    }
  }

}
