package com.wireweave.rest;

import com.wireweave.domain.User;
import com.wireweave.domain.port.ForPersistingUsers;
import com.wireweave.domain.port.ForRestartingContainers;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class AuthRestController {

    private final ForPersistingUsers forPersistingUsers;
    private final ForRestartingContainers containerRestarter;

    public AuthRestController(ForPersistingUsers forPersistingUsers, ForRestartingContainers containerRestarter) {
        this.forPersistingUsers = forPersistingUsers;
        this.containerRestarter = containerRestarter;
    }

    @GetMapping
    public List<User> getUsers() {
        return forPersistingUsers.getUsers();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void addUser(@RequestBody AddUserRequest request) {
        forPersistingUsers.addUser(request.username(), request.password(), request.email(), request.displayname());
        containerRestarter.restartContainer("authelia");
    }

    public record AddUserRequest(String username, String password, String email, String displayname) {}
}
