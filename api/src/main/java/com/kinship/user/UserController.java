package com.kinship.user;

import com.kinship.common.api.ApiException;
import com.kinship.common.security.UserPrincipal;
import com.kinship.user.dto.UpdateProfileRequest;
import com.kinship.user.dto.UserProfileResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserProfileResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return userService.profile(requirePrincipal(principal).id());
    }

    @PatchMapping("/me")
    public UserProfileResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                      @Valid @RequestBody UpdateProfileRequest request) {
        return userService.update(requirePrincipal(principal).id(), request);
    }

    private UserPrincipal requirePrincipal(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        return principal;
    }
}
