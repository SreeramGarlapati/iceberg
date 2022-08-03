/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.transforms;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.apache.iceberg.expressions.BoundPredicate;
import org.apache.iceberg.expressions.BoundTransform;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.UnboundPredicate;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

enum Dates implements Transform<Integer, Integer> {
  YEAR(ChronoUnit.YEARS, "year"),
  MONTH(ChronoUnit.MONTHS, "month"),
  DAY(ChronoUnit.DAYS, "day");

  private static final LocalDate EPOCH =
      Instant.ofEpochSecond(0).atOffset(ZoneOffset.UTC).toLocalDate();
  private final ChronoUnit granularity;
  private final String name;

  Dates(ChronoUnit granularity, String name) {
    this.granularity = granularity;
    this.name = name;
  }

  @Override
  public Integer apply(Integer days) {
    if (days == null) {
      return null;
    }

    if (granularity == ChronoUnit.DAYS) {
      return days;
    }

    if (days >= 0) {
      LocalDate date = EPOCH.plusDays(days);
      return (int) granularity.between(EPOCH, date);
    } else {
      // add 1 day to the value to account for the case where there is exactly 1 unit between the
      // date and epoch
      // because the result will always be decremented.
      LocalDate date = EPOCH.plusDays(days + 1);
      return (int) granularity.between(EPOCH, date) - 1;
    }
  }

  @Override
  public boolean canTransform(Type type) {
    return type.typeId() == Type.TypeID.DATE;
  }

  @Override
  public Type getResultType(Type sourceType) {
    if (granularity == ChronoUnit.DAYS) {
      return Types.DateType.get();
    }
    return Types.IntegerType.get();
  }

  @Override
  public boolean preservesOrder() {
    return true;
  }

  @Override
  public boolean satisfiesOrderOf(Transform<?, ?> other) {
    if (this == other) {
      return true;
    }

    if (other instanceof Dates) {
      // test the granularity, in days. day(ts) => 1 day, months(ts) => 30 days, and day satisfies
      // the order of months
      Dates otherTransform = (Dates) other;
      return granularity.getDuration().toDays()
          <= otherTransform.granularity.getDuration().toDays();
    }

    return false;
  }

  @Override
  public UnboundPredicate<Integer> project(String fieldName, BoundPredicate<Integer> pred) {
    if (pred.term() instanceof BoundTransform) {
      return ProjectionUtil.projectTransformPredicate(this, fieldName, pred);
    }

    if (pred.isUnaryPredicate()) {
      return Expressions.predicate(pred.op(), fieldName);

    } else if (pred.isLiteralPredicate()) {
      UnboundPredicate<Integer> projected =
          ProjectionUtil.truncateInteger(fieldName, pred.asLiteralPredicate(), this);
      if (this != DAY) {
        return ProjectionUtil.fixInclusiveTimeProjection(projected);
      }

      return projected;

    } else if (pred.isSetPredicate() && pred.op() == Expression.Operation.IN) {
      UnboundPredicate<Integer> projected =
          ProjectionUtil.transformSet(fieldName, pred.asSetPredicate(), this);
      if (this != DAY) {
        return ProjectionUtil.fixInclusiveTimeProjection(projected);
      }

      return projected;
    }

    return null;
  }

  @Override
  public UnboundPredicate<Integer> projectStrict(String fieldName, BoundPredicate<Integer> pred) {
    if (pred.term() instanceof BoundTransform) {
      return ProjectionUtil.projectTransformPredicate(this, fieldName, pred);
    }

    if (pred.isUnaryPredicate()) {
      return Expressions.predicate(pred.op(), fieldName);

    } else if (pred.isLiteralPredicate()) {
      UnboundPredicate<Integer> projected =
          ProjectionUtil.truncateIntegerStrict(fieldName, pred.asLiteralPredicate(), this);
      if (this != DAY) {
        return ProjectionUtil.fixStrictTimeProjection(projected);
      }

      return projected;

    } else if (pred.isSetPredicate() && pred.op() == Expression.Operation.NOT_IN) {
      UnboundPredicate<Integer> projected =
          ProjectionUtil.transformSet(fieldName, pred.asSetPredicate(), this);
      if (this != DAY) {
        return ProjectionUtil.fixStrictTimeProjection(projected);
      }

      return projected;
    }

    return null;
  }

  @Override
  public String toHumanString(Integer value) {
    if (value == null) {
      return "null";
    }

    switch (granularity) {
      case YEARS:
        return TransformUtil.humanYear(value);
      case MONTHS:
        return TransformUtil.humanMonth(value);
      case DAYS:
        return TransformUtil.humanDay(value);
      default:
        throw new UnsupportedOperationException("Unsupported time unit: " + granularity);
    }
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public String dedupName() {
    return "time";
  }
}
