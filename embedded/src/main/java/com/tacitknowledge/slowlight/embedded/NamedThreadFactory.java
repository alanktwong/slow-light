package com.tacitknowledge.slowlight.embedded;


import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by IntelliJ IDEA.
 * User: witherspore
 * Date: 6/21/13
 * Time: 9:50 AM
 *
 * Simple ThreadFactory implementation that provides names for threads and thread groups so its easier to log
 * and debug
 * If you are using Spring, consider the CustomizableThreadFactory instead
 * @see java.util.concurrent.ThreadFactory
 */
public class NamedThreadFactory implements ThreadFactory {

    // constants -----------------------------------------------------------------

    /**
     * Atomic for tracking the pool number when using multiple NamedThreadFactory objects
     */
    private static final AtomicInteger POOL_NUMBER = new AtomicInteger(1);
    /**
     * standard threadfactory key
     */
    public static final String THREADFACTORY = "threadfactory-";
    /**
     * standard group number key
     */
    public static final String GROUPNUMBER = "-groupnumber-";
    /**
     * standard thread key
     */
    public static final String THREAD = "-thread-";
    /**
     * standard parent group key
     */
    public static final String PARENTGROUP = "parentgroup-";
    /**
     *  standard group name key
     */
    public static final String GROUPNAME = "-groupname-";

    // internal vars -------------------------------------------------------------

    /**
     * Parent thread group from which the factory group descends
     */
    private final ThreadGroup parentGroup;
    /**
     * The factory thread group
     */
    private ThreadGroup group;
    /**
     * Thread number counter
     */
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    /**
     * the custom factory name
     */
    private final String factoryName;
    /**
     * The group's pool number from the static POOL_NUMBER
     */
    private final int factoryGroupNumber;

    // constructors --------------------------------------------------------------

    public NamedThreadFactory() {
        this("not named");
    }

    public NamedThreadFactory(String factoryName) {
        SecurityManager s = System.getSecurityManager();

        this.parentGroup = (s != null) ? s.getThreadGroup() :
                     Thread.currentThread().getThreadGroup();
        this.factoryGroupNumber = POOL_NUMBER.getAndIncrement();
        this.factoryName = factoryName;

    }

    /**
     * This group name includes the parent group name
     * format is parentgroup-<name>-groupname-<factoryName><factoryGroupNumber>
     * @return
     */
    private String getGroupName() {
        return PARENTGROUP + parentGroup.getName() +
                GROUPNAME + this.factoryName + getFactoryGroupNumber();
    }

    // ThreadFactory -------------------------------------------------------------
    /**
     * {@inheritDoc}
     */
    public Thread newThread(Runnable r) {

        if(group == null)
        {
            group = new ThreadGroup(this.parentGroup, getGroupName());
        }

        String threadName = getThreadName();

        Thread t = new Thread(this.group, r, threadName, 0L);
        if (t.isDaemon()) {
            t.setDaemon(false);
        }
        if (t.getPriority() != Thread.NORM_PRIORITY) {
            t.setPriority(Thread.NORM_PRIORITY);
        }
        return t;
    }

    /**
     * Generates the thread name to be used in the factory
     * @return thread name
     */
    String getThreadName() {
        return THREADFACTORY + this.factoryName + GROUPNUMBER +
                          getFactoryGroupNumber() + THREAD + this.threadNumber.getAndIncrement() + "]";
    }

    int getFactoryGroupNumber()
    {
        return factoryGroupNumber;
    }
}