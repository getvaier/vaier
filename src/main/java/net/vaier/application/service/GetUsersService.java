package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import net.vaier.application.GetUsersUseCase;
import net.vaier.domain.User;
import net.vaier.domain.port.ForPersistingUsers;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetUsersService implements GetUsersUseCase {

    private final ForPersistingUsers forPersistingUsers;

    @Override
    public List<User> getUsers() {
        return forPersistingUsers.getUsers();
    }
}
