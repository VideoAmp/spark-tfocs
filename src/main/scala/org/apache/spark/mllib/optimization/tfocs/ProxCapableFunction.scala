/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.optimization.tfocs

import org.apache.spark.mllib.linalg.{ DenseVector, Vector, Vectors }

/**
 * A trait for prox capable functions which support efficient proximity minimization, as expressed
 * by the proximity operator:
 *   x = prox_h(z, t) = argmin_x(h(x) + 0.5 * ||x - z||_2^2 / t)
 *
 * Both the minimizing x value and the function value h(x) may be computed, depending on the
 * mode specified.
 *
 * @tparam X A type representing a vector on which to evaluate the function.
 */
trait ProxCapableFunction[X] {

  /**
   * Evaluate the proximity operator prox_h at z with parameter t, returning both x and h(x)
   * depending on the mode specified.
   *
   * @param z The vector on which to evaluate the proximity operator.
   * @param t The proximity parameter.
   * @param mode The computation mode. If mode.f is true, h(x) is returned. If mode.g is true, x
   *        is returned.
   *
   * @return A Value containing x, the vector minimizing the proximity function prox_h, and/or h(x),
   *         the function value at x. The exact list of values computed and returned depends on the
   *         attributes of the supplied 'mode' parameter. The returned Value contains h(x) in its
   *         'f' attribute, while x is contained in the 'g' attribute.
   */
  def apply(z: X, t: Double, mode: Mode): Value[X]

  /** Evaluate the function h(x) at x. Does not perform proximity minimization. */
  def apply(x: X): Double
}

/**
 * The proximity operator for constant zero.
 *
 * NOTE In matlab tfocs this functionality is implemented in prox_0.m.
 */
class ProxZero extends ProxCapableFunction[Vector] {

  override def apply(z: Vector, t: Double, mode: Mode): Value[Vector] =
    Value(Some(0.0), Some(z))

  override def apply(x: Vector): Double = 0.0
}

/**
 * The proximity operator for the L1 norm.
 *
 * NOTE In matlab tfocs this functionality is implemented in prox_l1.m.
 */
class ProxL1(scale: Double) extends ProxCapableFunction[Vector] {

  override def apply(z: Vector, t: Double, mode: Mode): Value[Vector] = {
    val shrinkage = scale * t
    val g = shrinkage match {
      case 0.0 => z
      case _ => new DenseVector(z.toArray.map(z_i =>
        z_i * (1.0 - math.min(shrinkage / math.abs(z_i), 1.0))))
    }
    val f = if (mode.f) Some(apply(g)) else None
    Value(f, Some(g))
  }

  override def apply(x: Vector): Double = scale * Vectors.norm(x, 1)
}

/**
 * A projection onto the nonnegative orthant, implemented using a zero/infinity indicator function.
 *
 * NOTE In matlab tfocs this functionality is implemented in proj_Rplus.m.
 */
class ProjRPlus extends ProxCapableFunction[Vector] {

  override def apply(z: Vector, t: Double, mode: Mode): Value[Vector] = {

    val g = if (mode.g) {
      Some(new DenseVector(z.toArray.map(math.max(_, 0.0))))
    } else {
      None
    }

    Value(Some(0.0), g)
  }

  override def apply(x: Vector): Double = if (x.toArray.min < 0.0) Double.PositiveInfinity else 0.0
}

/**
 * A projection onto a simple box defined by upper and lower limits on each vector element,
 * implemented using a zero/infinity indicator function.
 *
 * NOTE In matlab tfocs this functionality is implemented in proj_box.m.
 */
class ProjBox(l: Vector, u: Vector) extends ProxCapableFunction[Vector] {

  val limits = l.toArray.zip(u.toArray)

  override def apply(z: Vector, t: Double, mode: Mode): Value[Vector] = {

    val g = if (mode.g) {
      Some(new DenseVector(z.toArray.zip(limits).map(y =>
        // Bound each element using the lower and upper limit for that element.
        math.min(y._2._2, math.max(y._2._1, y._1)))))
    } else {
      None
    }

    Value(Some(0.0), g)
  }

  override def apply(x: Vector): Double = if (x.toArray.zip(limits).exists(y =>
    // If an element is outside of that element's bounds, return infinity.
    y._1 > y._2._2 || y._1 < y._2._1)) { Double.PositiveInfinity } else { 0.0 }
}
