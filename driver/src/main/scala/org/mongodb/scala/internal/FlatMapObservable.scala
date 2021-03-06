/*
 * Copyright 2015 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.scala.internal

import org.mongodb.scala._

private[scala] case class FlatMapObservable[T, S](observable: Observable[T], f: T => Observable[S]) extends Observable[S] {

  // scalastyle:off cyclomatic.complexity method.length
  override def subscribe(observer: Observer[_ >: S]): Unit = {
    observable.subscribe(SubscriptionCheckingObserver(
      new Observer[T] {

        @volatile
        private var outerSubscription: Option[Subscription] = None
        @volatile
        private var nestedSubscription: Option[Subscription] = None
        @volatile
        private var started = false
        @volatile
        private var demand: Long = 0
        @volatile
        private var onCompleteCalled: Boolean = false

        override def onSubscribe(subscription: Subscription): Unit = {

          val masterSub = new Subscription() {
            override def isUnsubscribed: Boolean = subscription.isUnsubscribed

            override def request(n: Long): Unit = {
              if (n < 1) {
                throw new IllegalArgumentException(s"Number requested cannot be negative: $n")
              }

              val requestFirst = !started
              val localDemand = addDemand(n)
              if (!started) started = true

              requestFirst match {
                case true => subscription.request(1)
                case false => nestedSubscription match {
                  case Some(nestedSub) => nestedSub.request(localDemand)
                  case None            => subscription.request(1)
                }
              }
            }

            override def unsubscribe(): Unit = subscription.unsubscribe()
          }

          outerSubscription = Some(masterSub)
          observer.onSubscribe(masterSub)
        }

        override def onComplete(): Unit = {
          if (!onCompleteCalled) {
            onCompleteCalled = true
            if (nestedSubscription.isEmpty) observer.onComplete()
          }
        }

        override def onError(throwable: Throwable): Unit = observer.onError(throwable)

        override def onNext(tResult: T): Unit = {
          f(tResult).subscribe(
            new Observer[S]() {
              override def onError(throwable: Throwable): Unit = {
                nestedSubscription = None
                observer.onError(throwable)
              }

              override def onSubscribe(subscription: Subscription): Unit = {
                nestedSubscription = Some(subscription)
                subscription.request(demand)
              }

              override def onComplete(): Unit = {
                nestedSubscription = None
                onCompleteCalled match {
                  case true => observer.onComplete()
                  case false if demand > 0 =>
                    addDemand(-1) // reduce demand by 1 as it will be incremented by the outerSubscription
                    outerSubscription.get.request(1)
                  case false => // No more demand
                }
              }

              override def onNext(tResult: S): Unit = {
                addDemand(-1)
                observer.onNext(tResult)
              }
            }
          )
        }

        /**
         * Adds extra demand and protects against Longs rolling over
         *
         * @param extraDemand the amount of extra demand
         * @return the updated demand
         */
        private def addDemand(extraDemand: Long): Long = {
          this.synchronized {
            demand += extraDemand
            if (demand < 0) demand = Long.MaxValue
          }
          demand
        }
      }
    ))
  }
  // scalastyle:on cyclomatic.complexity method.length
}
