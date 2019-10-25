package io.quarkus.smallrye.health.runtime;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

public class RandomNumberGenerator implements Callable<Number> {

    @Override
    public Number call() {
        return ThreadLocalRandom.current().nextInt();
    }

}
