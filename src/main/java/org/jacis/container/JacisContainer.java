/*
 * Copyright (c) 2017. Jan Wiemer
 */
package org.jacis.container;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.jacis.exception.JacisNoTransactionException;
import org.jacis.exception.JacisStaleObjectException;
import org.jacis.plugin.JacisTransactionListener;
import org.jacis.plugin.txadapter.JacisTransactionAdapter;
import org.jacis.plugin.txadapter.local.JacisLocalTransaction;
import org.jacis.plugin.txadapter.local.JacisTransactionAdapterLocal;
import org.jacis.store.JacisStore;
import org.jacis.store.JacisStoreAdminInterface;
import org.jacis.store.JacisStoreImpl;
import org.jacis.store.JacisTransactionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//       _   _    ____ ___ ____
//      | | / \  / ___|_ _/ ___|
//   _  | |/ _ \| |    | |\___ \
//  | |_| / ___ \ |___ | | ___) |
//   \___/_/   \_\____|___|____/
//

/**
 * = The Jacis Container Holding the Stores for the Different Object Types
 *
 * The 'JacisContainer' is the main class of the Java ACI Store.
 * The container stores a number of individual stores for different object types.
 * Transactions are managed by the container and are valid for all stores in the container.
 * This class provides methods to create stores for different object types and provides access to those stores.
 *
 * @author Jan Wiemer
 */
public class JacisContainer {

  private static final Logger log = LoggerFactory.getLogger(JacisContainer.class);

  /** {@link JacisTransactionAdapter} to bind the Jacis Store to externally managed transactions. */
  private final JacisTransactionAdapter txAdapter;
  /** Map assigning the stores (values of type {@link JacisStoreImpl}) to the store identifiers (keys of type {@link StoreIdentifier}). */
  private final Map<StoreIdentifier, JacisStore<?, ?>> storeMap = new ConcurrentHashMap<>();
  /** List of transaction listeners / observers (type {@link JacisTransactionListener}) providing call-backs before / after prepare / internalCommit / rollback. */
  private final List<JacisTransactionListener> txListeners = new ArrayList<>();
  /** ThreadLocal storing the transaction info object for the last finished transaction */
  private ThreadLocal<JacisTransactionInfo> lastFinishedTransactionInfo = new ThreadLocal<>();

  /**
   * Create a container with the passed transaction adapter.
   * @param txAdapter The transaction adapter binding the container to  externally managed transactions
   */
  public JacisContainer(JacisTransactionAdapter txAdapter) {
    this.txAdapter = txAdapter;
  }

  /**
   * Create a container with a transaction adapter binding the store to locally managed transactions.
   * Transactions have to be started explicitly by calling the method {@link #beginLocalTransaction(String description)} in this case
   */
  public JacisContainer() {
    this(new JacisTransactionAdapterLocal());
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(#stores=" + storeMap.size() + ")";
  }

  /**
   * Register the passed transaction listener ({@link JacisTransactionListener}).
   * All registered listeners will be informed before / after each prepare / internalCommit / rollback of any transaction on this container.
   * @param listener The transaction listener to register.
   * @return This container itself for method chaining.
   */
  public JacisContainer registerTransactionListener(JacisTransactionListener listener) {
    txListeners.add(listener);
    return this;
  }

  /**
   * Create a store for the passed object type specification (type {@link JacisObjectTypeSpec}).
   * The passed specification determines the type of the keys and the type of the values stored in the created store.
   * @param objectTypeSpec object type specification describing the objects to be stored.
   * @return A reference to the created store (type {@link JacisStoreImpl})
   * @param <K> Key type of the store entry
   * @param <TV> Type of the objects in the transaction view. This is the type visible from the outside.
   * @param <CV> Type of the objects as they are stored in the internal map of committed values. This type is not visible from the outside.
   */
  public <K, TV, CV> JacisStoreAdminInterface<K, TV, CV> createStore(JacisObjectTypeSpec<K, TV, CV> objectTypeSpec) {
    StoreIdentifier storeIdentifier = new StoreIdentifier(objectTypeSpec.getKeyClass(), objectTypeSpec.getValueClass());
    JacisStoreImpl<K, TV, CV> store = new JacisStoreImpl<>(this, storeIdentifier, objectTypeSpec);
    storeMap.put(storeIdentifier, store);
    return store;
  }

  /**
   * Get the store (type {@link JacisStore}) for the passed key and value type.
   * @param keyClass Class of the keys that should be stored in the searched store
   * @param valueClass Class of the values that should be stored in the searched store
   * @return A reference to the found store (type {@link JacisStore}) (null if not found)
   * @param <K> Key type of the store entry
   * @param <TV> Type of the objects in the transaction view. This is the type visible from the outside.
   * @param <CV> Type of the objects as they are stored in the internal map of committed values. This type is not visible from the outside.
   */
  @SuppressWarnings("unchecked")
  public <K, TV, CV> JacisStore<K, TV> getStore(Class<K> keyClass, Class<TV> valueClass) {
    StoreIdentifier storeIdentifier = new StoreIdentifier(keyClass, valueClass);
    return (JacisStoreImpl<K, TV, CV>) storeMap.get(storeIdentifier);
  }

  /**
   * Get the store (type {@link JacisStoreAdminInterface}) for the passed key and value type.
   * @param keyClass Class of the keys that should be stored in the searched store
   * @param valueClass Class of the values that should be stored in the searched store
   * @return A reference to the found store (type {@link JacisStoreAdminInterface}) (null if not found)
   * @param <K> Key type of the store entry
   * @param <TV> Type of the objects in the transaction view. This is the type visible from the outside.
   * @param <CV> Type of the objects as they are stored in the internal map of committed values. This type is not visible from the outside.
   */
  @SuppressWarnings("unchecked")
  public <K, TV, CV> JacisStoreAdminInterface<K, TV, CV> getStoreAdminInterface(Class<K> keyClass, Class<TV> valueClass) {
    StoreIdentifier storeIdentifier = new StoreIdentifier(keyClass, valueClass);
    return (JacisStoreImpl<K, TV, CV>) storeMap.get(storeIdentifier);
  }

  /**
   * Clear all stored objects in all stores in this container.
   * The stores are cleared independently of any transaction context.
   * It is not necessary to start a transaction to call this method.
   * All ending transactions are invalidated, that means any attempt to prepare or internalCommit them is ignored (without an exception).
   */
  public synchronized void clearAllStores() {
    storeMap.values().forEach(JacisStore::clear);
  }

  /** @return If for the current thread there is a transaction active on this container. */
  public boolean isInTransaction() {
    return txAdapter.isTransactionActive();
  }

  /**
   * Start a locally managed transactions on the container.
   * The transaction is valid for all stores in the container.
   * Note that a locally managed transaction can only be started if the container
   * has been initialized with a transaction adapter for locally managed transactions
   * (calling the constructor {@link #JacisContainer()}. Otherwise an {@link IllegalStateException} is thrown.
   * The returned object represents the started transaction and provides method to internalCommit or rollback the transaction.
   * Note that each transaction is started with a description for logging and monitoring.
   * It is recommended to pass an explicit description by calling the method {@link #beginLocalTransaction(String)} ).
   * This convenience method tries to compute a default description by analyzing the call stack to determine the calling method.
   * @return An object representing the stated transaction (type {@link JacisLocalTransaction})
   * @throws IllegalStateException if the container was not initialized with transaction adapter for locally managed transactions.
   */
  public JacisLocalTransaction beginLocalTransaction() throws IllegalStateException {
    String description = Stream.of(new Exception("-").getStackTrace()) // go through the stack trace elements
        .filter(se -> !getClass().getName().equals(se.getClassName())) // ignore all stack trace elements for this class
        .map(StackTraceElement::toString).findFirst().orElse("-"); // use the first from outside (the calling method) as description
    return beginLocalTransaction(description);
  }

  /**
   * Start a locally managed transactions on the container.
   * The transaction is valid for all stores in the container.
   * Note that a locally managed transaction can only be started if the container
   * has been initialized with a transaction adapter for locally managed transactions
   * (calling the constructor {@link #JacisContainer()}. Otherwise an {@link IllegalStateException} is thrown.
   * The returned object represents the started transaction and provides method to internalCommit or rollback the transaction.
   * @param description a description of the transaction for logging and monitoring
   * @return An object representing the stated transaction (type {@link JacisLocalTransaction})
   * @throws IllegalStateException if the container was not initialized with transaction adapter for locally managed transactions.
   */
  public JacisLocalTransaction beginLocalTransaction(String description) throws IllegalStateException {
    if (txAdapter instanceof JacisTransactionAdapterLocal) {
      JacisTransactionAdapterLocal txAdapterLocal = (JacisTransactionAdapterLocal) txAdapter;
      lastFinishedTransactionInfo.remove();
      return txAdapterLocal.startLocalTransaction(this, description);
    } else {
      throw new IllegalStateException("Local transactions not supported! Local transactions need TX adapter " + JacisTransactionAdapterLocal.class.getSimpleName() + " but the configured is: " + txAdapter.getClass().getSimpleName());
    }
  }

  /**
   * Helper method executing the passed task (a {@link Runnable} usually passed as a lambda expression) within a locally started transaction.
   * First a locally managed transaction is started.
   * Then the passed task is executed.
   * If execution of the task succeeds the transaction is committed.
   * In case of any exception the transaction is rolled back.
   * @param task The task to execute inside a locally managed transaction
   * @throws IllegalStateException if the container was not initialized with transaction adapter for locally managed transactions.
   */
  public void withLocalTx(Runnable task) throws IllegalStateException {
    JacisLocalTransaction tx = beginLocalTransaction();
    Throwable txException = null;
    try {
      task.run();
      tx.prepare(); // phase 1 of the two phase internalCommit protocol
      tx.commit(); // phase 2 of the two phase internalCommit protocol
      tx = null;
    } catch (Throwable e) {
      txException = e;
      throw e;
    } finally {
      if (tx != null) { // if not committed roll it back
        try {
          tx.rollback();
        } catch (Throwable rollbackException) {
          RuntimeException exceptionToThrow = new RuntimeException("Rollback failed after " + txException, txException);
          exceptionToThrow.addSuppressed(rollbackException);
          //noinspection ThrowFromFinallyBlock
          throw exceptionToThrow;
        }
      }
    }
  }

  /**
   * Helper method executing the passed task (a {@link Runnable} usually passed as a lambda expression) within a locally started transaction.
   * If another transaction has concurrently updated an object that should be updated in this transaction as well a {@link JacisStaleObjectException} is thrown.
   * This method catches this exception and retries to execute the passed task inside a new transaction for the passed number of attempts.
   * If the {@link JacisStaleObjectException} is thrown repeatedly for all these attempts the exception is propagated to the caller.
   * In case of any other exception the transaction is rolled back and the exception is propagated to the caller immediately.
   * @param task The task to execute inside a locally managed transaction
   * @param retries Number of retries if transaction failed with {@link JacisStaleObjectException}
   * @throws IllegalStateException if the container was not initialized with transaction adapter for locally managed transactions.
   */
  public void withLocalTxAndRetry(int retries, Runnable task) {
    while (retries-- > 0) {
      try {
        withLocalTx(task);
        return; // if one attempt succeeds return immediately
      } catch (JacisStaleObjectException e) { // check if we retry
        log.warn("Stale object exception caught: {}", "" + e.getMessage());
        log.info("Detail message: \n{}", e.getDetails());
        if (retries == 0) {
          throw e;
        }
      } // other exceptions are not handled and are propagated to the caller
    } // END OF: while (retries-- > 0) {
  }

  /**
   * This method returns a handle for the (local or global) transaction currently associated with the store for the current thread.
   * The passed flag determines if a {@link JacisNoTransactionException} is thrown when no transaction is active for the current thread.
   * Otherwise the method simply returns null in case of a missing transaction.
   * If the container has not yet joined the transaction represented by the handle it is registered now.
   *
   * @param createIfAbsent A flag indicating if a new transaction should be started if no transaction is active
   * @return a handle (type {@link JacisTransactionHandle}) for the transaction currently associated with the current thread.
   * @throws JacisNoTransactionException If no transaction is active and the enforceTx flag is set to true
   */
  public JacisTransactionHandle getCurrentTransaction(boolean createIfAbsent) throws JacisNoTransactionException {
    JacisTransactionHandle handle = null;
    if (createIfAbsent) { // create a new TX if not yet present
      if (txAdapter.isTransactionActive()) { // JTA TX active -> create (if not yet created) and join
        handle = txAdapter.joinCurrentTransaction(this);
      } else { // no JTA active -> can not create and join
        throw new JacisNoTransactionException("No active transaction!");
      }
    } else { //  createIfAbsent == false // only return TX if already
      if (txAdapter.isTransactionActive()) {
        handle = txAdapter.joinCurrentTransaction(this);
      }
    }
    JacisTransactionInfo lastFinishedTxInfo = lastFinishedTransactionInfo.get();
    if(lastFinishedTxInfo!=null) {
      if (handle != null && !lastFinishedTxInfo.getTxId().equals(handle.getTxId())) {
        lastFinishedTransactionInfo.remove();
      }
    }
    return handle;
  }

  @SuppressWarnings("unused")
  public List<JacisTransactionInfo> getTransactionInfos() {
    Collection<JacisTransactionHandle> handles = txAdapter.getAllTransactionHandles();
    List<JacisTransactionInfo> res = new ArrayList<>(handles.size());
    long snapshotTimeMs = System.currentTimeMillis();
    for (JacisTransactionHandle txHandle : handles) {
      res.add(new JacisTransactionInfo(txHandle, this, storeMap.values(), snapshotTimeMs));
    }
    return res;
  }

  public JacisTransactionInfo getLastFinishedTransactionInfo() {
    return lastFinishedTransactionInfo.get();
  }

  public JacisTransactionInfo getTransactionInfo(Object externalTransaction) {
    JacisTransactionHandle txHandle = txAdapter.getTransactionHandle(externalTransaction);
    return getTransactionInfo(txHandle);
  }

  public JacisTransactionInfo getTransactionInfo(JacisTransactionHandle txHandle) {
    return txHandle == null ? null : new JacisTransactionInfo(txHandle, this, storeMap.values(), System.currentTimeMillis());
  }

  /**
   * Prepare the transaction represented by the passed transaction handle.
   * Note that this method usually is only called internally.
   * Usually the internalCommit is called either at the local transaction returned
   * by the method starting this transaction ({@link #beginLocalTransaction(String)}),
   * or by the external transaction framework via the transaction adapter.
   * @param transaction The transaction handle representing the transaction to prepare.
   */
  public synchronized void internalPrepare(JacisTransactionHandle transaction) {
    txListeners.forEach(l -> l.beforePrepare(this, transaction));
    for (JacisStore<?, ?> store : storeMap.values()) {
      ((JacisStoreTransactionAdapter) store).internalPrepare(transaction);
    }
    txListeners.forEach(l -> l.afterPrepare(this, transaction));
  }

  /**
   * Commit the transaction represented by the passed transaction handle.
   * Note that this method usually is only called internally.
   * Usually the internalCommit is called either at the local transaction returned
   * by the method starting this transaction ({@link #beginLocalTransaction(String)}),
   * or by the external transaction framework via the transaction adapter.
   * @param transaction The transaction handle representing the transaction to internalCommit.
   */
  public synchronized void internalCommit(JacisTransactionHandle transaction) {
    txListeners.forEach(l -> l.beforeCommit(this, transaction));
    for (JacisStore<?, ?> store : storeMap.values()) {
      ((JacisStoreTransactionAdapter) store).internalCommit(transaction);
    }
    JacisTransactionInfo txInfo = getTransactionInfo(transaction);
    if (txInfo != null) {
      lastFinishedTransactionInfo.set(txInfo);
    }
    for (JacisStore<?, ?> store : storeMap.values()) {
      ((JacisStoreTransactionAdapter) store).internalDestroy(transaction);
    }
    txListeners.forEach(l -> l.afterCommit(this, transaction));
    txAdapter.disjoinCurrentTransaction();
  }

  /**
   * Rollback the transaction represented by the passed transaction handle.
   * Note that this method usually is only called internally.
   * Usually the internalCommit is called either at the local transaction returned
   * by the method starting this transaction ({@link #beginLocalTransaction(String)}),
   * or by the external transaction framework via the transaction adapter.
   * @param transaction The transaction handle representing the transaction to rollback.
   */
  public synchronized void internalRollback(JacisTransactionHandle transaction) {
    txListeners.forEach(l -> l.beforeRollback(this, transaction));
    for (JacisStore<?, ?> store : storeMap.values()) {
      ((JacisStoreTransactionAdapter) store).internalRollback(transaction);
    }
    JacisTransactionInfo txInfo = getTransactionInfo(transaction);
    if (txInfo != null) {
      lastFinishedTransactionInfo.set(txInfo);
    }
    for (JacisStore<?, ?> store : storeMap.values()) {
      ((JacisStoreTransactionAdapter) store).internalDestroy(transaction);
    }
    txListeners.forEach(l -> l.afterRollback(this, transaction));
    txAdapter.disjoinCurrentTransaction();
  }

  /**
   * Store identifier uniquely identifying a store inside a container.
   * The store is identified by the type of the keys and the type of the values in the store.
   */
  public static class StoreIdentifier {

    /** Type of the keys in the store */
    private final Class<?> keyClass;
    /** Type of the values in the store */
    private final Class<?> valueClass;

    /**
     * Create a store identifier with the passed types for the keys and values.
     * @param keyClass Type of the keys in the store
     * @param valueClass Type of the values in the store
     */
    StoreIdentifier(Class<?> keyClass, Class<?> valueClass) {
      this.keyClass = keyClass;
      this.valueClass = valueClass;
    }

    /** @return The type of the keys in the store. */
    public Class<?> getKeyClass() {
      return keyClass;
    }

    /** @return The type of the values in the store. */
    public Class<?> getValueClass() {
      return valueClass;
    }

    public String toShortString() {
      return keyClass.getSimpleName() + "->" + valueClass.getSimpleName();
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "(keyClass=" + keyClass + ", valueClass=" + valueClass + ")";
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((keyClass == null) ? 0 : keyClass.hashCode());
      result = prime * result + ((valueClass == null) ? 0 : valueClass.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      } else if (obj == null) {
        return false;
      } else if (getClass() != obj.getClass()) {
        return false;
      }
      StoreIdentifier that = (StoreIdentifier) obj;
      if (this.keyClass == null ? that.keyClass == null : this.keyClass.equals(that.keyClass)) {
        if (this.valueClass == null ? that.valueClass == null : this.valueClass.equals(that.valueClass)) {
          return true;
        }
      }
      return false;
    }

  } // END OF:  public static class StoreIdentifier {

  public static abstract class JacisStoreTransactionAdapter {

    protected abstract void internalPrepare(JacisTransactionHandle transaction);

    protected abstract void internalCommit(JacisTransactionHandle transaction);

    protected abstract void internalRollback(JacisTransactionHandle transaction);

    protected abstract void internalDestroy(JacisTransactionHandle transaction);

  } // END OF:  public static abstract class JacisStoreTransactionAdapter {

}
