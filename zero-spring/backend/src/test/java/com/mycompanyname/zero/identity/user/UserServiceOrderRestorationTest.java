package com.mycompanyname.zero.identity.user;

import com.mycompanyname.zero.identity.domain.User;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q-03, the half that a database cannot be relied upon to reveal.
 *
 * <p>{@code UserService.list} pages the ids first and fetches the rows for those ids second.
 * {@code where id in (:ids)} carries no ordering guarantee, so {@code inOrderOf} exists to put
 * stage 2's rows back into stage 1's order.
 *
 * <p>{@code PagedListingIsNotSlicedInMemoryIT} asserts the same property over HTTP, but its ability
 * to FAIL depends on PostgreSQL happening to return the {@code in} list in some order other than the
 * requested one. That is true in practice and promised nowhere — a planner change could make the
 * integration test pass while the reordering step was missing. These cases hand the method a result
 * that is definitely shuffled, so the guard cannot become accidentally vacuous.
 */
class UserServiceOrderRestorationTest {

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static List<Long> idsOf(List<User> users) {
        List<Long> ids = new ArrayList<>();
        users.forEach(user -> ids.add(user.getId()));
        return ids;
    }

    @Test
    void theSecondQuerysOrderIsDiscardedInFavourOfThePagesOrder() {
        List<Long> page = List.of(30L, 10L, 20L);
        List<User> shuffled = List.of(user(10L), user(20L), user(30L));

        assertThat(idsOf(UserService.inOrderOf(page, shuffled)))
                .as("the page decided the order; the fetch query is only a lookup. Returning the "
                        + "fetch order gives the caller the right rows in the wrong order, which "
                        + "every count, total and content assertion in the suite would still pass")
                .containsExactly(30L, 10L, 20L);
    }

    @Test
    void alreadyMatchingOrderIsLeftAlone() {
        List<Long> page = List.of(1L, 2L, 3L);

        assertThat(idsOf(UserService.inOrderOf(page, List.of(user(1L), user(2L), user(3L)))))
                .containsExactly(1L, 2L, 3L);
    }

    /**
     * A collection fetch join returns one row per associated element, so the same entity can appear
     * several times in stage 2's list. The page must still hold it once.
     */
    @Test
    void duplicatesFromTheFetchJoinCollapseToOneRow() {
        List<Long> page = List.of(2L, 1L);
        User two = user(2L);
        List<User> withDuplicates = List.of(user(1L), two, two, user(1L), two);

        assertThat(idsOf(UserService.inOrderOf(page, withDuplicates)))
                .as("a fetch join multiplies rows; the page size must not multiply with it")
                .containsExactly(2L, 1L);
    }

    /**
     * A row deleted between the two queries has no entity to hydrate. Dropping it from the page is
     * the honest outcome; a null in the content list would NPE in the DTO mapper several frames
     * later, and failing the whole request would turn someone else's delete into this caller's 500.
     */
    @Test
    void anIdThatVanishedBetweenTheTwoQueriesIsDroppedNotNull() {
        List<Long> page = List.of(1L, 2L, 3L);

        List<User> result = UserService.inOrderOf(page, List.of(user(3L), user(1L)));

        assertThat(idsOf(result)).containsExactly(1L, 3L);
        assertThat(result).doesNotContainNull();
    }

    @Test
    void anEmptyPageStaysEmpty() {
        assertThat(UserService.inOrderOf(List.of(), List.of())).isEmpty();
    }
}
