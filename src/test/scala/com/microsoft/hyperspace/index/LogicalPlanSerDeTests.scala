/*
 * Copyright (2020) The Hyperspace Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.hyperspace.index

import org.apache.hadoop.fs.Path
import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.{And, Attribute, AttributeReference, EqualTo, Exists, InSubquery, IsNotNull, ListQuery, Literal, NamedExpression, ScalarSubquery, ScalaUDF}
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}

import com.microsoft.hyperspace.SparkInvolvedSuite
import com.microsoft.hyperspace.index.serde.LogicalPlanSerDeUtils

/**
 * Some tests are adapted from examples in ExpressionParserSuite.scala, PlanParserSuite.scala,
 * and QueryPlanSuite.scala.
 */
class LogicalPlanSerDeTests extends SparkFunSuite with SparkInvolvedSuite {
  val c1: AttributeReference = AttributeReference("c1", StringType)()
  val c2: AttributeReference = AttributeReference("c2", StringType)()
  val c3: AttributeReference = AttributeReference("c3", StringType)()
  val c4: AttributeReference = AttributeReference("c4", IntegerType)()

  var scanNode: LogicalRelation = _
  var singleTablePlan: LogicalPlan = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    val tableSchema = schemaFromAttributes(c1, c2, c3, c4)
    val tableLocation =
      new InMemoryFileIndex(
        spark,
        Seq(new Path("baseTableLocation")),
        Map.empty,
        Some(tableSchema),
        NoopCache)
    val relation = HadoopFsRelation(
      tableLocation,
      new StructType(),
      tableSchema,
      None,
      new ParquetFileFormat,
      Map.empty)(spark)
    scanNode = LogicalRelation(relation, Seq(c1, c2, c3, c4), None, isStreaming = false)
    singleTablePlan = Project(
      Seq(c1, c2, c3),
      Filter(And(IsNotNull(c3), EqualTo(c3, Literal("facebook"))), scanNode))
  }

  private def schemaFromAttributes(attributes: Attribute*): StructType = {
    StructType(attributes.map(a => StructField(a.name, a.dataType, a.nullable, a.metadata)))
  }

  test("Serde query with Hadoop file system relation.") {
    verifyPlanSerde(scanNode, "hadoopFsRelation.plan")
  }

  test("Serde query with scalar subquery.") {
    val scalarSubquery = ScalarSubquery(singleTablePlan, Seq.empty, NamedExpression.newExprId)
    val plan = Filter(EqualTo(c3, scalarSubquery), scanNode)
    verifyPlanSerde(plan, "scalarSubquery.plan")
  }

  test("Serde query with list subquery.") {
    val listSubquery =
      ListQuery(singleTablePlan, Seq.empty, NamedExpression.newExprId, Seq.empty)
    val plan = Filter(listSubquery, scanNode)
    verifyPlanSerde(plan, "listSubquery.plan")
  }

  test("Serde of InSubquery") {
    val listSubquery =
      ListQuery(singleTablePlan, Seq.empty, NamedExpression.newExprId, Seq.empty)
    val inSubquery = InSubquery(Seq(EqualTo(c3, c1)), listSubquery)
    val plan = Filter(inSubquery, scanNode)
    verifyPlanSerde(plan, "listSubquery.plan")
  }

  test("Serde query with exists subquery.") {
    val existsSubquery =
      Exists(singleTablePlan, Seq.empty, NamedExpression.newExprId)
    val plan = Filter(existsSubquery, scanNode)
    verifyPlanSerde(plan, "existsSubquery.plan")
  }

  test("Serde query with scala UDF.") {
    val intUdf = ScalaUDF(null, IntegerType, Literal(1) :: Nil, true :: Nil)
    val plan = Filter(intUdf, scanNode)
    verifyPlanSerde(plan, "scalaUdf.plan")
  }

  test("Serde query with intersect.") {
    val plan = Intersect(singleTablePlan, singleTablePlan, isAll = true)
    verifyPlanSerde(plan, "intersect.plan")
  }

  test("Serde query with except.") {
    val plan = Except(singleTablePlan, singleTablePlan, isAll = true)
    verifyPlanSerde(plan, "except.plan")
  }

  test("Serde query with view definition.") {
    val cte = SubqueryAlias("testCte", scanNode)
    val plan = With(singleTablePlan, List(("testAlias", cte)))
    verifyPlanSerde(plan, "viewDef.plan")
  }

  test("Serde of single table plan.") {
    val plan = Aggregate(Seq(c1), Seq(c2, c3), singleTablePlan)
    verifyPlanSerde(plan, "single-table.plan")
  }

  test("Serde query with join.") {
    val joinCondition = EqualTo(c1, c2)
    val plan = Join(singleTablePlan, singleTablePlan, JoinType("inner"), Some(joinCondition))
    verifyPlanSerde(plan, "join.plan")
  }

  private def verifyPlanSerde(plan: LogicalPlan, planFileName: String): Unit = {
    // Serialize the plan to byte array.
    val serialized = LogicalPlanSerDeUtils.serialize(plan, spark)

    // Verify correctness of deserialized plan.
    val deserialized = LogicalPlanSerDeUtils.deserialize(serialized, spark)
    assert(plan fastEquals deserialized)
  }
}
