/*
 * Copyright (c) 2016. Jan Wiemer
 */

package org.jacis.plugin.txadapter.local;

import org.jacis.container.JacisContainer;
import org.jacis.container.JacisTransactionHandle;
import org.jacis.exception.JacisNoTransactionException;
import org.jacis.exception.JacisTransactionAlreadyStartedException;
import org.jacis.plugin.txadapter.JacisTransactionAdapter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Jan Wiemer
 *
 * Default implementation of the transaction adapter using local transactions. 
 */
public class JacisTransactionAdapterLocal implements JacisTransactionAdapter {

  protected final ThreadLocal<JacisTransactionHandle> transaction = new ThreadLocal<>();
  private final AtomicLong txSeq = new AtomicLong(0);

  public JacisLocalTransaction startLocalTransaction(JacisContainer jacisContainer, String txDescription) {
    JacisTransactionHandle tx = transaction.get();
    if (tx != null) {
      throw new JacisTransactionAlreadyStartedException("Transaction already started: " + tx);
    }
    long txNr = txSeq.incrementAndGet();
    String txId = "TX-" + txNr;
    JacisLocalTransaction extTxObj = new JacisLocalTransaction(txId);
    tx = new JacisTransactionHandle(txId, txDescription, extTxObj);
    joinCurrentTransaction(tx, jacisContainer);
    transaction.set(tx);
    return extTxObj;
  }

  @Override
  public void joinCurrentTransaction(JacisTransactionHandle transaction, JacisContainer container) {
    Object extTx = transaction.getExternalTransaction();
    if (extTx instanceof JacisLocalTransaction) {
      JacisLocalTransaction localTx = (JacisLocalTransaction) extTx;
      localTx.joinCurrentTransaction(transaction, container);
    } else {
      throw new IllegalArgumentException("No local Transaction: " + extTx);
    }
  }

  @Override
  public JacisTransactionHandle getCurrentTransaction(boolean enforce) throws JacisNoTransactionException {
    JacisTransactionHandle tx = transaction.get();
    if (tx == null && enforce) {
      throw new JacisNoTransactionException("No active transaction!");
    }
    return tx;
  }

  @Override
  public void destroyCurrentTransaction() {
    transaction.remove();
  }

}