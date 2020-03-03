package com.tacitknowledge.slowlight.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * User: witherspore
 * Date: 6/19/13
 * Time: 7:59 AM
 *
 * While custom implementations of the DegradationStrategy can be done, this DefaultDegradationStrategy
 * provides some out of the box capabilities for delaying service calls and creating errors based on handler
 * thread pool utilization
 */

public class DefaultDegradationStrategy implements DegradationStrategy {

    public static final double FIFTY_PERCENT = 0.5;
    public static final double TWO = 2.0;
    public static final double ONE_HUNDRED_PERCENT = 1.0;
    public static final double ONE_QUARTER = 0.25;
    /**
     * standard message for randomly generated exceptions
     */
    public static final String GENERATED_BY_DEGRADATION_PROXY = "Generated by degradation embedded.";

    /**
     * base delay to apply to service calls
     */
    final private Long serviceDemandTime;
    /**
     * max delay or timeout for service calls
     */
    final private Long serviceTimeout;
    /**
     * What percentage of calls should fail, based on the specific call response time delay compared to
     * the service timeout
     *
     * This means that only under high utilization and long actual delays will the calls fail
     */
    final private double passRate;
    /**
     * An array of exceptions that may be thrown if a failure occurs.  One of these will be instantiated
     */
    final private Class<Exception>[] randomExceptions;
    /**
     * An error object to return if the service needs to simulate this on failures
     */
    final private Object errorObject;
    /**
     * Should it return error objects or throw exceptions on failures?
     */
    final private FailurePriority failurePriority;
    /**
     * If a failure is indicated, should it occur before a delay or after?
     */
    final private FastFail fastFail;

    /**
     * List of methods to degrade.  If empty, all methods are degraded.
     */
    final private List<Method> degradedMethods;

    /**
     * should the handler use future.get or future.get(timeout)
     */
    final private Boolean timeoutQueues;

    public DefaultDegradationStrategy(Long serviceDemandTime,
                                      Long serviceTimeout,
                                      double passRate,
                                      Class<Exception>[] randomExceptions,
                                      Object errorObject,
                                      FailurePriority failurePriority,
                                      FastFail fastFail,
                                      Boolean timeoutQueues,
                                      Method[] degradedMethods) {
        this.serviceDemandTime = Math.max(0L, serviceDemandTime);
        this.serviceTimeout = Math.max(serviceDemandTime, serviceTimeout);
        this.passRate = passRate;
        this.randomExceptions = randomExceptions;
        this.errorObject = errorObject;
        this.failurePriority = failurePriority;
        this.fastFail = fastFail;
        this.timeoutQueues = timeoutQueues;
        this.degradedMethods = Arrays.asList(degradedMethods);

    }

    public DefaultDegradationStrategy(Long serviceDemandTime,
                                      Long serviceTimeout,
                                      double passRate,
                                      Class<Exception>[] randomExceptions,
                                      Object errorObject,
                                      FailurePriority failurePriority,
                                      FastFail fastFail,
                                      Boolean timeoutQueues
    ) {
        this(
                serviceDemandTime,
                serviceTimeout,
                passRate,
                randomExceptions,
                errorObject,
                failurePriority,
                fastFail,
                timeoutQueues,
                new Method[]{}
        );

    }


    public DefaultDegradationStrategy(Long serviceDemandTime,
                                      Long serviceTimeout,
                                      double passRate,
                                      Class<Exception>[] randomExceptions) {
        this(serviceDemandTime,
                serviceTimeout,
                passRate,
                randomExceptions,
                new Method[]{}
        );
    }

    public DefaultDegradationStrategy(Long serviceDemandTime,
                                      Long serviceTimeout,
                                      double passRate,
                                      Class<Exception>[] randomExceptions, Method[] degradedMethods) {
        this(serviceDemandTime,
                serviceTimeout,
                passRate,
                randomExceptions,
                null,
                FailurePriority.EXCEPTION,
                FastFail.FALSE,
                Boolean.FALSE,
                degradedMethods
        );
    }

    public DefaultDegradationStrategy(Long serviceDemandTime, Long serviceTimeout, double passRate) {
        this(serviceDemandTime,
                serviceTimeout,
                passRate,
                new Method[]{}
        );
    }

    public DefaultDegradationStrategy(Long serviceDemandTime, Long serviceTimeout, double passRate,
                                      Method[] degradedMethods) {
        this(serviceDemandTime,
                serviceTimeout,
                passRate,
                new Class[]{}, degradedMethods
        );
    }

    /**
     * The base amount of any delay.  Should be considered the minimum response time of a service under ideal conditions
     * Alternatively, set it high to simulate very slow services
     *
     * @return base delay time in millis
     */
    public Long getServiceDemandTime() {
        return serviceDemandTime;
    }

    /**
     * An approximation of when a service would time out.  For instance, if a remote service times out at 30 seconds,
     * set this to 30000 millis
     *
     * @return approximate timeout of service in millis
     */
    public Long getServiceTimeout() {
        return serviceTimeout;
    }

    /**
     * @return random millis between service demand time * 0.75 and service demand time * 1.25
     */
    public Long getRandomizedServiceDemandTime() {
        double betweenNegativeOneQuarterandOneQuarter = randomInRange(-1.0 * ONE_QUARTER, ONE_QUARTER);
        return Math.round(getServiceDemandTime() * (ONE_HUNDRED_PERCENT + betweenNegativeOneQuarterandOneQuarter));
    }

    /**
     * This creates basically a DTO or bean representing how the handler and DegradationCallable should evaluate
     * a specific call.  It sets up the specific delay for the call, whether it should fail,
     * any error objects, exceptions to be thrown, and passes on the failure priorities and fast fail mode
     *
     * @param handler, represents the specific call
     * @return DegradationPlan for a specific call
     */
    public DegradationPlan generateDegradationPlan(DegradationHandler handler) {
        //solve for area under exp curve such that errorThresholdPercent represents the point accurately
        // for instance, 10% error rate will have a passRate of approx. exp(0.9347) == 90%

        Long adjustedResponseTime = calculateAdjustedResponseTime(handler);
        Boolean shouldFail = checkForFailure(adjustedResponseTime);

        final DegradationPlan degradationPlan = new DegradationPlan(adjustedResponseTime,
                generateRandomException(),
                getErrorObject(),
                shouldFail,
                fastFail,
                failurePriority);
        return degradationPlan;
    }

    /**
     * Generates a result in millis that will be the delay set in the DegradationPlan and used in the DegradationCallable
     * for thread sleep time.
     *
     * This is roughly between the service demand time and service timeout, based on an exponential curve that factors
     * in current handler thread pool utilization.  It can range from slightly lower than the demand time to slightly
     * higher than the timeout.
     *
     * When slightly higher than timeout, the handler will timeout the future.get if timeouts is enabled.
     *
     *
     * @param handler, represents the specific call
     * @return millis to sleep the thread
     */
    Long calculateAdjustedResponseTime(DegradationHandler handler) {
        return Math.max(getRandomizedServiceDemandTime(), Math.round(
                (ONE_HUNDRED_PERCENT + randomInRange(-0.25, 0.25))
                        * getServiceDemandTime()
                        * Math.exp(handler.getPercentUtilized())
                        * handler.getPercentUtilized() * getServiceTimeout() / (getServiceDemandTime() * Math.exp(1))
        ));

    }

    /**
     * Returns a random number between two doubles
     */
    double randomInRange(double min, double max) {
        Random random = new Random();
        double range = max - min;
        double scaled = random.nextDouble() * range;
        double shifted = scaled + min;
        return shifted; // == (rand.nextDouble() * (max-min)) + min;
    }


    /**
     * Converts the pass rate between 0 and 1 to a result that represents the percentage under an exponential curve
     * rather than flat line.
     *
     * This pretty much runs the integral between 0 and the pass rate for the natural exponential curve, then divides
     * by the total area.
     *
     */
    double findUtilizationThresholdForPassRate() {
        final double utilThreshold = (passRate >= ONE_HUNDRED_PERCENT)
                ? ONE_HUNDRED_PERCENT
                //basic integral calculus for percentage of area under an exponential curve
                : Math.log(passRate * (Math.exp(1) - 1) + 1);
        return utilThreshold;
    }

    /**
     * Compares the adjustedResponseTime (sleep delay) to the exponential curve between service demand
     * and service timeout.
     *
     * If this exponent adjustedResponseTime is greater that the exponent adjusted utilization threshold for the pass
     * rate, then it will return true, indicating the call should fail.
     *
     * @param adjustedResponseTime, sleep delay
     * @return flag indicating whether call should fail
     */
    protected Boolean checkForFailure(double adjustedResponseTime) {

        if (passRate >= 1.0)
            return Boolean.FALSE;
        Boolean shouldFail = Boolean.FALSE;
        if (adjustedResponseTime > getServiceTimeout() * findUtilizationThresholdForPassRate()) {
            shouldFail = Boolean.TRUE;
        }

        return shouldFail;
    }

    /**
     * Generally called when generating the DegradationPlan for a DegradationCallable
     *
     * @return the error object configured for return if FailurePriority.EXCEPTION is set
     */
    public Object getErrorObject() {
        return errorObject;
    }

    /**
     * Called when generating the DegradationPlan for a DegradationCallable
     *
     * Picks a random exception from the randomExceptions list and tries to instantiate it using
     * a constructor with the String parameter.  If this can not be done, it will try and use the default constructor
     *
     * @return randomly generated exception
     */
    public Exception generateRandomException() {
        Exception exception = null;
        if (randomExceptions.length != 0) {
            Integer index = (int) (Math.random() * (double) randomExceptions.length);
            try {
                for (Constructor constructor : randomExceptions[index].getConstructors()) {
                    if (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0].equals(String.class))
                        exception = (Exception) constructor.newInstance(GENERATED_BY_DEGRADATION_PROXY);
                }
                if (exception == null) {
                    exception = randomExceptions[index].newInstance();
                }
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }
        return exception;
    }

    /**
     * The default strategy just passes through calls to targets via method invocation.  Sub-classes may override
     * @param  targetCallback to be called
     * @return result of call override
     * @throws Exception that could be thrown if call fails
     */
    public Object overrideResult(final TargetCallback targetCallback) throws Exception
    {
        if (targetCallback == null)
        {
            throw new NullPointerException("Target callback cannot be null");
        }

        return targetCallback.execute();
    }

    /**
     * Should the handler use future.get with timeouts or not.  Timeouts will be set to the service timeout field
     * @return true if timeouts are enforced
     * @see java.util.concurrent.Future
     */
    public Boolean isTimeoutQueues() {
        return timeoutQueues;
    }

    /**
     * What percentage of calls under the logarithmic curve between service demand and timeout should pass.
     * 1.0 indicates all should pass and it should never return an error object or throw an exception
     * @return percentage of calls between 0.0 and 1.0
     */
    public Double getPassRate() {
        return passRate;
    }

    /**
     * If pass rate is 1.0, service demand time is 0, and service timeout is 0, then no proxyserver degradation
     * will occur.  Handler uses this to skip adding the call to the Thread Pool and just executes the call in
     * the current thread
     * @return true if it should run in current thread
     */
    public Boolean shouldSkipDegradation() {
        return getPassRate() == 1.0 && getServiceDemandTime() == 0L && getServiceTimeout() == 0L;
    }

    /**
     * If degraded methods was empty, return false as all methods should be degraded.  If its non-empty, only return
     * false for methods matching the list.
     *
     * @param method to test
     * @return false if method should not be degraded.
     */
    public Boolean isMethodExcluded(Method method) {
        //fast exit for none specified.  should always degrade when empty
        if (degradedMethods.isEmpty()) {
            return Boolean.FALSE;
        }
        //attempt to match method
        for (Method degradedMethod : degradedMethods) {
            if (method.getName().equals(degradedMethod.getName())) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;

    }
}
