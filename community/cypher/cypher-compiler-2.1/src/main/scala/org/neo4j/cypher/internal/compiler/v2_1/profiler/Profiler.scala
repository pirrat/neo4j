/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_1.profiler

import org.neo4j.cypher.internal.compiler.v2_1._
import pipes.{NullPipe, QueryState, Pipe, PipeDecorator}
import org.neo4j.cypher.internal.compiler.v2_1.spi.{DelegatingOperations, Operations, QueryContext, DelegatingQueryContext}
import org.neo4j.cypher.ProfilerStatisticsNotReadyException
import org.neo4j.graphdb.{PropertyContainer, Direction, Relationship, Node}
import collection.mutable
import org.neo4j.cypher.internal.compiler.v2_1.PlanDescription.Arguments

class Profiler extends PipeDecorator {

  val contextStats: mutable.Map[Pipe, ProfilingQueryContext] = mutable.Map.empty
  val iterStats: mutable.Map[Pipe, ProfilingIterator] = mutable.Map.empty


  def decorate(pipe: Pipe, iter: Iterator[ExecutionContext]): Iterator[ExecutionContext] = decoratePipe(pipe, iter) {
    val resultIter = new ProfilingIterator(iter)

    iterStats(pipe) = resultIter
    resultIter
  }

  def decorate(pipe: Pipe, state: QueryState): QueryState = decoratePipe(pipe, state) {
    val decoratedContext = state.query match {
      case p: ProfilingQueryContext => new ProfilingQueryContext(p.inner, pipe)
      case _                        => new ProfilingQueryContext(state.query, pipe)
    }

    contextStats(pipe) = decoratedContext
    state.copy(query = decoratedContext)
  }

  private def decoratePipe[T](pipe: Pipe, default: T)(f: => T): T = pipe match {
    case _:NullPipe => default
    case _ => f
  }

  def decorate(plan: PlanDescription, isProfileReady: => Boolean): PlanDescription = {
    plan map {
      p: PlanDescription =>
        val iteratorStats: ProfilingIterator = iterStats(p.pipe)

        if (!isProfileReady)
          throw new ProfilerStatisticsNotReadyException()

        val planWithRows = p.addArgument(Arguments.Rows(iteratorStats.count))

        contextStats.get(p.pipe) match {
          case Some(stats) => planWithRows.addArgument(Arguments.DbHits(stats.count))
          case None        => planWithRows
        }
    }
  }
}

trait Counter {
  private var _count = 0L

  def count = _count

  def increment() {
    _count += 1L
  }
}

final class ProfilingQueryContext(val inner: QueryContext, val p: Pipe) extends DelegatingQueryContext(inner) with Counter {

  self =>

  override protected def singleDbHit[A](value: A): A = {
    increment()
    value
  }

  override protected def manyDbHits[A](value: Iterator[A]): Iterator[A] = {
    increment()
    value.map {
      (v) =>
        increment()
        v
    }
  }

  class ProfilerOperations[T <: PropertyContainer](inner: Operations[T]) extends DelegatingOperations[T](inner) {
    override protected def singleDbHit[A](value: A): A = self.singleDbHit(value)
    override protected def manyDbHits[A](value: Iterator[A]): Iterator[A] = self.manyDbHits(value)
  }

  override def nodeOps: Operations[Node] = new ProfilerOperations(inner.nodeOps)
  override def relationshipOps: Operations[Relationship] = new ProfilerOperations(inner.relationshipOps)
}

class ProfilingIterator(inner: Iterator[ExecutionContext]) extends Iterator[ExecutionContext] with Counter {

  def hasNext: Boolean = inner.hasNext

  def next(): ExecutionContext = {
    increment()
    inner.next()
  }
}