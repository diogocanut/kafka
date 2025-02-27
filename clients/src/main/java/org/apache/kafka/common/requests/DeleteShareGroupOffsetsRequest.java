/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.common.requests;

import org.apache.kafka.common.message.DeleteShareGroupOffsetsRequestData;
import org.apache.kafka.common.message.DeleteShareGroupOffsetsResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ByteBufferAccessor;
import org.apache.kafka.common.protocol.Errors;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DeleteShareGroupOffsetsRequest extends AbstractRequest {
    public static class Builder extends AbstractRequest.Builder<DeleteShareGroupOffsetsRequest> {

        private final DeleteShareGroupOffsetsRequestData data;

        public Builder(DeleteShareGroupOffsetsRequestData data) {
            this(data, false);
        }

        public Builder(DeleteShareGroupOffsetsRequestData data, boolean enableUnstableLastVersion) {
            super(ApiKeys.DELETE_SHARE_GROUP_OFFSETS, enableUnstableLastVersion);
            this.data = data;
        }

        @Override
        public DeleteShareGroupOffsetsRequest build(short version) {
            return new DeleteShareGroupOffsetsRequest(data, version);
        }

        @Override
        public String toString() {
            return data.toString();
        }
    }

    private final DeleteShareGroupOffsetsRequestData data;

    public DeleteShareGroupOffsetsRequest(DeleteShareGroupOffsetsRequestData data, short version) {
        super(ApiKeys.DELETE_SHARE_GROUP_OFFSETS, version);
        this.data = data;
    }

    @Override
    public DeleteShareGroupOffsetsResponse getErrorResponse(int throttleTimeMs, Throwable e) {
        List<DeleteShareGroupOffsetsResponseData.DeleteShareGroupOffsetsResponseTopic> results = new ArrayList<>();
        data.topics().forEach(
            topicResult -> results.add(new DeleteShareGroupOffsetsResponseData.DeleteShareGroupOffsetsResponseTopic()
                .setTopicName(topicResult.topicName())
                .setPartitions(topicResult.partitions().stream()
                    .map(partitionData -> new DeleteShareGroupOffsetsResponseData.DeleteShareGroupOffsetsResponsePartition()
                        .setPartitionIndex(partitionData)
                        .setErrorCode(Errors.forException(e).code()))
                    .collect(Collectors.toList()))));
        return new DeleteShareGroupOffsetsResponse(new DeleteShareGroupOffsetsResponseData()
            .setResponses(results));
    }

    @Override
    public DeleteShareGroupOffsetsRequestData data() {
        return data;
    }

    public static DeleteShareGroupOffsetsRequest parse(ByteBuffer buffer, short version) {
        return new DeleteShareGroupOffsetsRequest(
            new DeleteShareGroupOffsetsRequestData(new ByteBufferAccessor(buffer), version),
            version
        );
    }
}