package sn.recozai.identity.adapter;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.*;

interface UserRepository extends JpaRepository<UserEntity,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserEntity> findByNormalizedLogin(String login);
    @Override @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserEntity> findById(UUID id);
}
