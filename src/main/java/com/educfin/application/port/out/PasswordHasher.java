package com.educfin.application.port.out;

/**
 * Porta de saída para hashing e verificação de senhas.
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
