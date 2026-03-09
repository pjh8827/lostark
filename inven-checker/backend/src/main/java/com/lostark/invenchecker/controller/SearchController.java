package com.lostark.invenchecker.controller;

import com.lostark.invenchecker.model.BoardPost;
import com.lostark.invenchecker.service.InvenSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SearchController {

    private final InvenSearchService searchService;

    public SearchController(InvenSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "닉네임을 입력해주세요."));
        }
        if (nickname.length() < 2 || nickname.length() > 12) {
            return ResponseEntity.badRequest().body(Map.of("error", "닉네임은 2~12글자여야 합니다."));
        }
        if (!nickname.matches("[가-힣a-zA-Z0-9]+")) {
            return ResponseEntity.badRequest().body(Map.of("error", "닉네임은 한글, 영문, 숫자만 허용됩니다."));
        }

        try {
            List<BoardPost> posts = searchService.search(nickname);
            return ResponseEntity.ok(posts);
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "인벤 검색 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }
}
