package com.lostark.invenchecker.controller;

import com.lostark.invenchecker.model.BoardPost;
import com.lostark.invenchecker.model.CharacterInfo;
import com.lostark.invenchecker.service.InvenSearchService;
import com.lostark.invenchecker.service.LostArkApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private static final int TOP_CHARACTER_LIMIT = 6;
    /** 최대 6개 캐릭터를 동시에 검색 */
    private static final ExecutorService CHAR_EXECUTOR = Executors.newFixedThreadPool(TOP_CHARACTER_LIMIT);

    private final InvenSearchService searchService;
    private final LostArkApiService lostArkApiService;

    public SearchController(InvenSearchService searchService, LostArkApiService lostArkApiService) {
        this.searchService = searchService;
        this.lostArkApiService = lostArkApiService;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String nickname,
            @RequestParam(required = false) String apiKey) {

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
            if (apiKey != null && !apiKey.isBlank()) {
                return enhancedSearch(nickname, apiKey.trim());
            } else {
                List<BoardPost> posts = searchService.search(nickname);
                return ResponseEntity.ok(posts);
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "인벤 검색 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 강화 검색: Lost Ark Open API로 동일 계정의 모든 캐릭터를 조회한 뒤
     * 아이템 레벨 상위 {@value TOP_CHARACTER_LIMIT}개 닉네임으로 사사게를 검색합니다.
     *
     * <p>중복 제거: 게시글 링크의 쿼리 파라미터를 제거한 경로(post ID)를 키로 사용합니다.
     * 같은 게시글이 여러 닉네임 검색에서 발견되더라도 한 번만 노출됩니다.
     */
    private ResponseEntity<?> enhancedSearch(String nickname, String apiKey) throws IOException {
        // 1. Lost Ark API로 형제 캐릭터 조회
        List<CharacterInfo> siblings;
        try {
            siblings = lostArkApiService.getSiblings(nickname, apiKey);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }

        if (siblings.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // 2. 아이템 레벨 기준 내림차순 정렬 후 상위 N개 선택
        List<CharacterInfo> topCharacters = siblings.stream()
                .sorted(Comparator.comparingDouble(
                        (CharacterInfo c) -> parseItemLevel(c.getItemAvgLevel())).reversed())
                .limit(TOP_CHARACTER_LIMIT)
                .collect(Collectors.toList());

        List<CharacterInfo> validCharacters = topCharacters.stream()
                .filter(c -> c.getCharacterName() != null && c.getCharacterName().length() >= 2)
                .collect(Collectors.toList());

        log.info("[강화 검색] '{}' 계정 전체 {}개 → 상위 {}개 캐릭터 병렬 검색 시작",
                nickname, siblings.size(), validCharacters.size());
        validCharacters.forEach(c -> log.info("  - {} ({})", c.getCharacterName(), c.getItemAvgLevel()));

        // 3. 모든 캐릭터를 동시에 검색
        List<CompletableFuture<List<BoardPost>>> futures = validCharacters.stream()
                .map(c -> {
                    String charName = c.getCharacterName();
                    return CompletableFuture.supplyAsync(() -> {
                        try { return searchService.search(charName); }
                        catch (IOException e) {
                            log.warn("[강화 검색] '{}' 검색 실패: {}", charName, e.getMessage());
                            return List.<BoardPost>of();
                        }
                    }, CHAR_EXECUTOR);
                })
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 4. 결과 병합 (dedup 키: 쿼리 파라미터 제거한 경로)
        LinkedHashMap<String, BoardPost> resultMap = new LinkedHashMap<>();
        for (int i = 0; i < validCharacters.size(); i++) {
            String charName = validCharacters.get(i).getCharacterName();
            for (BoardPost post : futures.get(i).join()) {
                String dedupKey = stripQuery(post.getLink());
                if (!resultMap.containsKey(dedupKey)) {
                    post.setMatchedNickname(charName);
                    resultMap.put(dedupKey, post);
                }
            }
        }

        List<BoardPost> results = new ArrayList<>(resultMap.values());
        log.info("[강화 검색 완료] 총 {}건 수집 (캐릭터 {}개 병렬 검색)", results.size(), validCharacters.size());
        return ResponseEntity.ok(results);
    }

    /** "1,640.83" → 1640.83 으로 변환. 파싱 실패 시 0.0 반환. */
    private double parseItemLevel(String level) {
        if (level == null || level.isBlank()) return 0.0;
        try {
            return Double.parseDouble(level.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** URL에서 ? 이후 쿼리 파라미터를 제거합니다. */
    private String stripQuery(String url) {
        int idx = url.indexOf('?');
        return idx >= 0 ? url.substring(0, idx) : url;
    }
}
