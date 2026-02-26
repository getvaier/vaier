package com.wireweave.rest;

import com.wireweave.domain.User;
import com.wireweave.domain.port.ForPersistingUsers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/users")
public class AuthRestController {

    private final ForPersistingUsers forPersistingUsers;

    public AuthRestController(ForPersistingUsers forPersistingUsers) {
        this.forPersistingUsers = forPersistingUsers;
    }

    @GetMapping
    public List<User> getUsers() {
        return forPersistingUsers.getUsers();
    }
}
