package com.wireweave.rest;

import com.wireweave.domain.User;
import com.wireweave.domain.port.ForPersistingUsers;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void addUser(@RequestBody AddUserRequest request) {
        forPersistingUsers.addUser(request.username(), request.password(), request.email(), request.displayname());
    }

    public record AddUserRequest(String username, String password, String email, String displayname) {}
}
