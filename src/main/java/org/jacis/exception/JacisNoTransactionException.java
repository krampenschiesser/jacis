/*
 * Copyright (c) 2016. Jan Wiemer
 */

package org.jacis.exception;

/**
 * Exception thrown if an operation is called on a store that requires to run inside a transaction,
 * but there is no transaction is active.
 *
 * @author Jan Wiemer
 */
public class JacisNoTransactionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public JacisNoTransactionException(String message) {
    super(message);
  }

}
