/**
 * Copyright 2012 the original author or authors.
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
package io.informant.core.config;

import javax.annotation.concurrent.Immutable;

import com.google.common.base.Objects;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * Immutable structure to hold the fine-grained profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class FineProfilingConfig {

    private static final Gson gson = new Gson();

    private final boolean enabled;

    // percentage of traces to apply fine profiling, between 0.0 and 100.0
    private final double tracePercentage;
    private final int intervalMillis;
    private final int totalSeconds;
    // persistence threshold of -1 means use core config persistence threshold
    // for fine-grained profiled traces, the real threshold is the minimum of this and the core
    // threshold
    private final int persistenceThresholdMillis;

    static FineProfilingConfig getDefaultInstance() {
        return new Builder().build();
    }

    static FineProfilingConfig fromJson(String json) throws JsonSyntaxException {
        return gson.fromJson(json, FineProfilingConfig.Builder.class).build();
    }

    public static Builder builder(FineProfilingConfig base) {
        return new Builder(base);
    }

    private FineProfilingConfig(boolean enabled, double tracePercentage, int intervalMillis,
            int totalSeconds, int persistenceThresholdMillis) {

        this.enabled = enabled;
        this.tracePercentage = tracePercentage;
        this.intervalMillis = intervalMillis;
        this.totalSeconds = totalSeconds;
        this.persistenceThresholdMillis = persistenceThresholdMillis;
    }

    public String toJson() {
        return gson.toJson(this);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getTracePercentage() {
        return tracePercentage;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    public int getTotalSeconds() {
        return totalSeconds;
    }

    public int getPersistenceThresholdMillis() {
        return persistenceThresholdMillis;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("tracePercentage", tracePercentage)
                .add("intervalMillis", intervalMillis)
                .add("totalSeconds", totalSeconds)
                .add("persistenceThresholdMillis", persistenceThresholdMillis)
                .toString();
    }

    public static class Builder {

        private boolean enabled = true;
        private double tracePercentage = 0;
        private int intervalMillis = 50;
        private int totalSeconds = 10;
        private int persistenceThresholdMillis = -1;

        private Builder() {}
        private Builder(FineProfilingConfig base) {
            enabled = base.enabled;
            tracePercentage = base.tracePercentage;
            intervalMillis = base.intervalMillis;
            totalSeconds = base.totalSeconds;
            persistenceThresholdMillis = base.persistenceThresholdMillis;
        }
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        public Builder tracePercentage(double tracePercentage) {
            this.tracePercentage = tracePercentage;
            return this;
        }
        public Builder intervalMillis(int intervalMillis) {
            this.intervalMillis = intervalMillis;
            return this;
        }
        public Builder totalSeconds(int totalSeconds) {
            this.totalSeconds = totalSeconds;
            return this;
        }
        public Builder persistenceThresholdMillis(int persistenceThresholdMillis) {
            this.persistenceThresholdMillis = persistenceThresholdMillis;
            return this;
        }
        public FineProfilingConfig build() {
            return new FineProfilingConfig(enabled, tracePercentage, intervalMillis, totalSeconds,
                    persistenceThresholdMillis);
        }
    }
}