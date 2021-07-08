/*
 * Copyright The Stargate Authors
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
package io.stargate.graphql.schema.graphqlfirst.fetchers.deployed;

import com.google.common.collect.ImmutableMap;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetchingEnvironment;
import io.stargate.auth.Scope;
import io.stargate.auth.SourceAPI;
import io.stargate.auth.TypedKeyValue;
import io.stargate.auth.UnauthorizedException;
import io.stargate.db.query.BoundDelete;
import io.stargate.db.query.builder.AbstractBound;
import io.stargate.db.query.builder.BuiltCondition;
import io.stargate.db.schema.Keyspace;
import io.stargate.graphql.schema.graphqlfirst.processor.DeleteModel;
import io.stargate.graphql.schema.graphqlfirst.processor.EntityModel;
import io.stargate.graphql.schema.graphqlfirst.processor.MappingModel;
import io.stargate.graphql.schema.graphqlfirst.processor.OperationModel.ReturnType;
import io.stargate.graphql.schema.graphqlfirst.processor.OperationModel.SimpleReturnType;
import io.stargate.graphql.schema.graphqlfirst.processor.ResponsePayloadModel;
import io.stargate.graphql.schema.graphqlfirst.processor.ResponsePayloadModel.TechnicalField;
import io.stargate.graphql.web.StargateGraphqlContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class DeleteFetcher extends MutationFetcher<DeleteModel, DataFetcherResult<Object>> {

  public DeleteFetcher(DeleteModel model, MappingModel mappingModel) {
    super(model, mappingModel);
  }

  @Override
  protected MutationPayload<DataFetcherResult<Object>> getPayload(
      DataFetchingEnvironment environment, StargateGraphqlContext context)
      throws UnauthorizedException {
    EntityModel entityModel = model.getEntity();
    Keyspace keyspace = context.getDataStore().schema().keyspace(entityModel.getKeyspaceName());

    // We're either getting the values from a single entity argument, or individual PK field
    // arguments:
    java.util.function.Predicate<String> hasArgument;
    Function<String, Object> getArgument;
    if (model.getEntityArgumentName().isPresent()) {
      Map<String, Object> entity = environment.getArgument(model.getEntityArgumentName().get());
      hasArgument = entity::containsKey;
      getArgument = entity::get;
    } else {
      hasArgument = environment::containsArgument;
      getArgument = environment::getArgument;
    }

    List<BuiltCondition> whereConditions =
        bindWhere(
            model.getWhereConditions(),
            hasArgument,
            getArgument,
            entityModel::validateNoFiltering,
            keyspace);
    List<BuiltCondition> ifConditions =
        bindIf(model.getIfConditions(), hasArgument, getArgument, keyspace);
    AbstractBound<?> query =
        context
            .getDataStore()
            .queryBuilder()
            .delete()
            .from(entityModel.getKeyspaceName(), entityModel.getCqlName())
            .where(whereConditions)
            .ifs(ifConditions)
            .ifExists(model.ifExists())
            .build()
            .bind();

    List<TypedKeyValue> primaryKey = TypedKeyValue.forDML((BoundDelete) query);
    context
        .getAuthorizationService()
        .authorizeDataWrite(
            context.getSubject(),
            entityModel.getKeyspaceName(),
            entityModel.getCqlName(),
            primaryKey,
            Scope.DELETE,
            SourceAPI.GRAPHQL);

    return new MutationPayload<>(
        query,
        primaryKey,
        queryResults -> {
          DataFetcherResult.Builder<Object> result = DataFetcherResult.newResult();
          assert queryResults.size() == 1;
          MutationResult queryResult = queryResults.get(0);
          if (queryResult instanceof MutationResult.Failure) {
            result.error(
                toGraphqlError(
                    (MutationResult.Failure) queryResult,
                    getCurrentFieldLocation(environment),
                    environment));
          } else {
            boolean applied = queryResult instanceof MutationResult.Applied;
            ReturnType returnType = model.getReturnType();
            if (returnType == SimpleReturnType.BOOLEAN) {
              result.data(applied);
            } else {
              ResponsePayloadModel payload = (ResponsePayloadModel) returnType;
              if (payload.getTechnicalFields().contains(TechnicalField.APPLIED)) {
                result.data(ImmutableMap.of(TechnicalField.APPLIED.getGraphqlName(), applied));
              } else {
                result.data(Collections.emptyMap());
              }
            }
          }
          return result.build();
        });
  }
}
