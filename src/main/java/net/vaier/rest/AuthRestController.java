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
import net.vaier.domain.AuthMode;
import net.vaier.domain.User;
import net.vaier.domain.VaierHostnames;
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
        // Validation -> 400, duplicate -> 409 (ConflictException), infra failure -> 500
        // are all rendered as ApiError by GlobalExceptionHandler.
        addUserUseCase.addUser(request.username(), request.password(), request.email(),
                request.displayname(), request.groups());
        return ResponseEntity.ok("User added successfully");
    }

    @DeleteMapping("/users/{username}")
    public ResponseEntity<String> deleteUser(@PathVariable String username) {
        deleteUserUseCase.deleteUser(username);
        return ResponseEntity.ok("User deleted successfully");
    }

    @PutMapping("/users/{username}/password")
    public ResponseEntity<String> changePassword(
        @PathVariable String username,
        @RequestBody ChangePasswordRequest request
    ) {
        changePasswordUseCase.changePassword(username, request.newPassword());
        return ResponseEntity.ok("Password changed successfully");
    }

    @PutMapping("/users/{username}/email")
    public ResponseEntity<String> updateEmail(
        @PathVariable String username,
        @RequestBody UpdateEmailRequest request
    ) {
        updateUserEmailUseCase.updateEmail(username, request.email());
        return ResponseEntity.ok("Email updated successfully");
    }

    @PutMapping("/users/{username}/displayname")
    public ResponseEntity<String> updateDisplayName(
        @PathVariable String username,
        @RequestBody UpdateDisplayNameRequest request
    ) {
        updateUserDisplayNameUseCase.updateDisplayName(username, request.displayname());
        return ResponseEntity.ok("Display name updated successfully");
    }

    @PutMapping("/users/{username}/groups")
    public ResponseEntity<String> updateUserGroups(
        @PathVariable String username,
        @RequestBody UpdateGroupsRequest request
    ) {
        updateUserGroupsUseCase.updateUserGroups(username, request.groups());
        return ResponseEntity.ok("Groups updated successfully");
    }

    @GetMapping("/users/me")
    public ResponseEntity<MeResponse> getMe(
            @RequestHeader(value = "Remote-User", required = false) String username,
            @RequestHeader(value = "Remote-Name", required = false) String displayname,
            @RequestHeader(value = "Remote-Email", required = false) String email) {
        String domain = configResolver.getDomain();
        boolean hasDomain = domain != null && !domain.isBlank();
        // The Vaier console itself stays Authelia-gated in 3a (#305). The logout URL is resolved
        // through the mode-aware domain helper so that when the console moves to social login (3b)
        // only the AuthMode argument changes — social-gated *services* already log out via
        // oauth2-proxy's sign-out (see VaierHostnames#logoutUrl).
        VaierHostnames hostnames = new VaierHostnames(domain);
        String console = hasDomain ? "https://" + hostnames.vaierServerFqdn() + "/" : null;
        String logoutUrl = hasDomain ? hostnames.logoutUrl(AuthMode.AUTHELIA, console) : null;
        String loginUrl = hasDomain ? "https://" + hostnames.autheliaHost() + "/?rd=" + console : null;
        return ResponseEntity.ok(new MeResponse(username, displayname, email, logoutUrl, loginUrl));
    }

    @GetMapping("/groups")
    public List<String> getGroups() {
        return getGroupsUseCase.getGroups();
    }

    @DeleteMapping("/groups/{groupName}")
    public ResponseEntity<String> deleteGroup(@PathVariable String groupName) {
        deleteGroupUseCase.deleteGroup(groupName);
        return ResponseEntity.ok("Group deleted successfully");
    }

    public record AddUserRequest(String username, String password, String email, String displayname, List<String> groups) {}
    public record ChangePasswordRequest(String newPassword) {}
    public record UpdateEmailRequest(String email) {}
    public record UpdateDisplayNameRequest(String displayname) {}
    public record UpdateGroupsRequest(List<String> groups) {}
    public record MeResponse(String username, String displayname, String email, String logoutUrl, String loginUrl) {}
}
