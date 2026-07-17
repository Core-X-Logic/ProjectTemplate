package com.mycompanyname.zero.audit.history;

import com.mycompanyname.zero.audit.domain.EntityChange;
import com.mycompanyname.zero.audit.domain.EntityChangeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists buffered entity changes in a fresh transaction, after the originating business
 * transaction has committed. Running in {@code REQUIRES_NEW} keeps history writes off the caller's
 * flush cycle (avoiding re-entrancy during the Hibernate event) and out of the caller's rollback.
 */
@Service
@RequiredArgsConstructor
public class EntityChangeWriter {

    private final EntityChangeRepository entityChangeRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeAll(List<EntityChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        entityChangeRepository.saveAll(changes);
    }
}
