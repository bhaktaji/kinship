package com.kinship.user;

import com.kinship.user.dto.InterestsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InterestController {

    private final UserService userService;

    public InterestController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/v1/interests")
    public InterestsResponse interests() {
        return new InterestsResponse(userService.interests());
    }
}
