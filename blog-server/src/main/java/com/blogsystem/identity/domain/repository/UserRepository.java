package com.blogsystem.identity.domain.repository;

import com.blogsystem.identity.domain.model.User;
import com.blogsystem.identity.domain.model.UserId;

import java.util.Optional;

/**
 * Domain port for identity aggregate persistence.
 */
public interface UserRepository {
    Optional<User> findById(UserId id);

    User save(User user);
}
