package com.educfin.application.port.out;

import com.educfin.domain.user.Email;
import com.educfin.domain.user.User;
import com.educfin.domain.user.UserId;

import java.util.Optional;

/**
 * Porta de saída para persistência do usuário.
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findById(UserId id);

    Optional<User> findByEmail(Email email);

    boolean existsByEmail(Email email);
}
