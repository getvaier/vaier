package com.wireweave.rest;

import com.wireweave.domain.User;
import com.wireweave.domain.port.ForPersistingUsers;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserRestController {

    private final ForPersistingUsers userPersistence;

    public UserRestController(ForPersistingUsers userPersistence) {
        this.userPersistence = userPersistence;
    }

    @GetMapping
    public List<User> getUsers() {
        return userPersistence.getUsers();
    }

    @PostMapping
    public ResponseEntity<String> addUser(@RequestBody AddUserRequest request) {
        try {
            userPersistence.addUser(
                request.getUsername(),
                request.getPassword(),
                request.getEmail(),
                request.getDisplayname()
            );
            return ResponseEntity.ok("User added successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<String> deleteUser(@PathVariable String username) {
        try {
            userPersistence.deleteUser(username);
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
            userPersistence.changePassword(username, request.getNewPassword());
            return ResponseEntity.ok("Password changed successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Data
    public static class AddUserRequest {
        private String username;
        private String password;
        private String email;
        private String displayname;
    }

    @Data
    public static class ChangePasswordRequest {
        private String newPassword;
    }
}
