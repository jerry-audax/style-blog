package com.blogsystem.domain.identity.adapter.repository;

import com.blogsystem.domain.identity.model.User;
import com.blogsystem.domain.identity.model.UserId;

import java.util.Optional;

/**
 * Domain port for identity aggregate persistence.
 */
public interface UserRepository {
    Optional<User> findById(UserId id);

    User save(User user);
}
