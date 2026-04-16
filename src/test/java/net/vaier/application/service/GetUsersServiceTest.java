package net.vaier.application.service;

import net.vaier.domain.User;
import net.vaier.domain.port.ForPersistingUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetUsersServiceTest {

    @Mock ForPersistingUsers forPersistingUsers;
    @InjectMocks GetUsersService service;

    @Test
    void getUsers_delegatesToPort() {
        User user = mock(User.class);
        when(forPersistingUsers.getUsers()).thenReturn(List.of(user));

        assertThat(service.getUsers()).containsExactly(user);
    }

    @Test
    void getUsers_returnsEmptyListWhenNoUsers() {
        when(forPersistingUsers.getUsers()).thenReturn(List.of());

        assertThat(service.getUsers()).isEmpty();
    }
}
