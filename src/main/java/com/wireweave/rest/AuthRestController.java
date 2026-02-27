package com.wireweave.rest;

import com.wireweave.domain.User;
import com.wireweave.domain.port.ForPersistingUsers;
import com.wireweave.domain.port.ForRestartingContainers;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<String> addUser(@RequestBody AddUserRequest request) {
        try {
            forPersistingUsers.addUser(request.username(), request.password(), request.email(), request.displayname());
            containerRestarter.restartContainer("authelia");
            return ResponseEntity.ok("User added successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<String> deleteUser(@PathVariable String username) {
        try {
            forPersistingUsers.deleteUser(username);
            containerRestarter.restartContainer("authelia");
            return ResponseEntity.ok("User deleted successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{username}/password")
    public ResponseEntity<String> changePassword(
        @PathVariable String username,
        @RequestBody ChangePasswordRequest request
    ) {
        try {
            forPersistingUsers.changePassword(username, request.newPassword());
            containerRestarter.restartContainer("authelia");
            return ResponseEntity.ok("Password changed successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public record AddUserRequest(String username, String password, String email, String displayname) {}
    public record ChangePasswordRequest(String newPassword) {}
}
