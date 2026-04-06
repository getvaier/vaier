package net.vaier.rest;

import net.vaier.application.AddUserUseCase;
import net.vaier.application.ChangePasswordUseCase;
import net.vaier.application.DeleteUserUseCase;
import net.vaier.domain.User;
import net.vaier.domain.port.ForPersistingUsers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class AuthRestController {

    @Value("${VAIER_DOMAIN:}")
    private String vaierDomain;

    private final ForPersistingUsers forPersistingUsers;
    private final AddUserUseCase addUserUseCase;
    private final DeleteUserUseCase deleteUserUseCase;
    private final ChangePasswordUseCase changePasswordUseCase;

    public AuthRestController(ForPersistingUsers forPersistingUsers, AddUserUseCase addUserUseCase, DeleteUserUseCase deleteUserUseCase, ChangePasswordUseCase changePasswordUseCase) {
        this.forPersistingUsers = forPersistingUsers;
        this.addUserUseCase = addUserUseCase;
        this.deleteUserUseCase = deleteUserUseCase;
        this.changePasswordUseCase = changePasswordUseCase;
    }

    @GetMapping
    public List<User> getUsers() {
        return forPersistingUsers.getUsers();
    }

    @PostMapping
    public ResponseEntity<String> addUser(@RequestBody AddUserRequest request) {
        try {
            addUserUseCase.addUser(request.username(), request.password(), request.email(), request.displayname());
            return ResponseEntity.ok("User added successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<String> deleteUser(@PathVariable String username) {
        try {
            deleteUserUseCase.deleteUser(username);
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

    @GetMapping("/me")
    public ResponseEntity<MeResponse> getMe(
            @RequestHeader(value = "Remote-User", required = false) String username) {
        String logoutUrl = (vaierDomain != null && !vaierDomain.isBlank())
                ? "https://login." + vaierDomain + "/logout"
                : null;
        return ResponseEntity.ok(new MeResponse(username, logoutUrl));
    }

    public record AddUserRequest(String username, String password, String email, String displayname) {}
    public record ChangePasswordRequest(String newPassword) {}
    public record MeResponse(String username, String logoutUrl) {}
}
