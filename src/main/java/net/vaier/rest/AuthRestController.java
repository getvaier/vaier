package net.vaier.rest;

import net.vaier.application.ChangePasswordUseCase;
import net.vaier.domain.User;
import net.vaier.domain.port.ForPersistingUsers;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class AuthRestController {

    private final ForPersistingUsers forPersistingUsers;
    private final ChangePasswordUseCase changePasswordUseCase;

    public AuthRestController(ForPersistingUsers forPersistingUsers, ChangePasswordUseCase changePasswordUseCase) {
        this.forPersistingUsers = forPersistingUsers;
        this.changePasswordUseCase = changePasswordUseCase;
    }

    @GetMapping
    public List<User> getUsers() {
        return forPersistingUsers.getUsers();
    }

    @PostMapping
    public ResponseEntity<String> addUser(@RequestBody AddUserRequest request) {
        try {
            forPersistingUsers.addUser(request.username(), request.password(), request.email(), request.displayname());
            return ResponseEntity.ok("User added successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<String> deleteUser(@PathVariable String username) {
        try {
            forPersistingUsers.deleteUser(username);
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
            changePasswordUseCase.changePassword(username, request.newPassword());
            return ResponseEntity.ok("Password changed successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public record AddUserRequest(String username, String password, String email, String displayname) {}
    public record ChangePasswordRequest(String newPassword) {}
}
