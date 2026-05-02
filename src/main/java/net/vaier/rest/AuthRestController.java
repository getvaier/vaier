package net.vaier.rest;

import net.vaier.application.AddUserUseCase;
import net.vaier.application.ChangePasswordUseCase;
import net.vaier.application.DeleteGroupUseCase;
import net.vaier.application.DeleteUserUseCase;
import net.vaier.application.GetGroupsUseCase;
import net.vaier.application.GetUsersUseCase;
import net.vaier.application.UpdateUserDisplayNameUseCase;
import net.vaier.application.UpdateUserEmailUseCase;
import net.vaier.application.UpdateUserGroupsUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.User;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class AuthRestController {

    private final ConfigResolver configResolver;
    private final GetUsersUseCase getUsersUseCase;
    private final AddUserUseCase addUserUseCase;
    private final DeleteUserUseCase deleteUserUseCase;
    private final ChangePasswordUseCase changePasswordUseCase;
    private final UpdateUserEmailUseCase updateUserEmailUseCase;
    private final UpdateUserDisplayNameUseCase updateUserDisplayNameUseCase;
    private final GetGroupsUseCase getGroupsUseCase;
    private final UpdateUserGroupsUseCase updateUserGroupsUseCase;
    private final DeleteGroupUseCase deleteGroupUseCase;

    public AuthRestController(ConfigResolver configResolver,
                              GetUsersUseCase getUsersUseCase,
                              AddUserUseCase addUserUseCase,
                              DeleteUserUseCase deleteUserUseCase,
                              ChangePasswordUseCase changePasswordUseCase,
                              UpdateUserEmailUseCase updateUserEmailUseCase,
                              UpdateUserDisplayNameUseCase updateUserDisplayNameUseCase,
                              GetGroupsUseCase getGroupsUseCase,
                              UpdateUserGroupsUseCase updateUserGroupsUseCase,
                              DeleteGroupUseCase deleteGroupUseCase) {
        this.configResolver = configResolver;
        this.getUsersUseCase = getUsersUseCase;
        this.addUserUseCase = addUserUseCase;
        this.deleteUserUseCase = deleteUserUseCase;
        this.changePasswordUseCase = changePasswordUseCase;
        this.updateUserEmailUseCase = updateUserEmailUseCase;
        this.updateUserDisplayNameUseCase = updateUserDisplayNameUseCase;
        this.getGroupsUseCase = getGroupsUseCase;
        this.updateUserGroupsUseCase = updateUserGroupsUseCase;
        this.deleteGroupUseCase = deleteGroupUseCase;
    }

    @GetMapping("/users")
    public List<User> getUsers() {
        return getUsersUseCase.getUsers();
    }

    @PostMapping("/users")
    public ResponseEntity<String> addUser(@RequestBody AddUserRequest request) {
        try {
            addUserUseCase.addUser(request.username(), request.password(), request.email(),
                    request.displayname(), request.groups());
            return ResponseEntity.ok("User added successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/users/{username}")
    public ResponseEntity<String> deleteUser(@PathVariable String username) {
        try {
            deleteUserUseCase.deleteUser(username);
            return ResponseEntity.ok("User deleted successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/users/{username}/password")
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

    @PutMapping("/users/{username}/email")
    public ResponseEntity<String> updateEmail(
        @PathVariable String username,
        @RequestBody UpdateEmailRequest request
    ) {
        try {
            updateUserEmailUseCase.updateEmail(username, request.email());
            return ResponseEntity.ok("Email updated successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/users/{username}/displayname")
    public ResponseEntity<String> updateDisplayName(
        @PathVariable String username,
        @RequestBody UpdateDisplayNameRequest request
    ) {
        try {
            updateUserDisplayNameUseCase.updateDisplayName(username, request.displayname());
            return ResponseEntity.ok("Display name updated successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/users/{username}/groups")
    public ResponseEntity<String> updateUserGroups(
        @PathVariable String username,
        @RequestBody UpdateGroupsRequest request
    ) {
        try {
            updateUserGroupsUseCase.updateUserGroups(username, request.groups());
            return ResponseEntity.ok("Groups updated successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/users/me")
    public ResponseEntity<MeResponse> getMe(
            @RequestHeader(value = "Remote-User", required = false) String username,
            @RequestHeader(value = "Remote-Name", required = false) String displayname,
            @RequestHeader(value = "Remote-Email", required = false) String email) {
        String domain = configResolver.getDomain();
        boolean hasDomain = domain != null && !domain.isBlank();
        String logoutUrl = hasDomain ? "https://login." + domain + "/logout?rd=https://vaier." + domain + "/" : null;
        String loginUrl = hasDomain ? "https://login." + domain + "/?rd=https://vaier." + domain + "/" : null;
        return ResponseEntity.ok(new MeResponse(username, displayname, email, logoutUrl, loginUrl));
    }

    @GetMapping("/groups")
    public List<String> getGroups() {
        return getGroupsUseCase.getGroups();
    }

    @DeleteMapping("/groups/{groupName}")
    public ResponseEntity<String> deleteGroup(@PathVariable String groupName) {
        try {
            deleteGroupUseCase.deleteGroup(groupName);
            return ResponseEntity.ok("Group deleted successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public record AddUserRequest(String username, String password, String email, String displayname, List<String> groups) {}
    public record ChangePasswordRequest(String newPassword) {}
    public record UpdateEmailRequest(String email) {}
    public record UpdateDisplayNameRequest(String displayname) {}
    public record UpdateGroupsRequest(List<String> groups) {}
    public record MeResponse(String username, String displayname, String email, String logoutUrl, String loginUrl) {}
}
