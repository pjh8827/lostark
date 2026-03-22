package com.lostark.invenchecker.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.lostark.invenchecker.model.BoardPost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * InvenSearchService 테스트 (병렬 fetch 구조)
 *
 * 검색 흐름:
 *   1. 첫 페이지 fetch (sterm 없음) → 시작 sterm 파악
 *   2. 나머지 (maxIterations-1)개 페이지를 병렬 fetch (sterm = base + i*10000)
 *   3. 게시글 본문 대상자 확인 (병렬)
 *
 * 스텁 전략:
 *   - stubFirstPage  : sterm 조건 없음 (가장 먼저 등록 → 낮은 우선순위)
 *   - stubAllSterm   : sterm 파라미터가 있는 모든 요청 (나중 등록 → 높은 우선순위)
 *   두 규칙 모두 stype/svalue 조건 포함.
 *   → no-sterm 요청 → stubFirstPage 응답
 *   → any-sterm 요청 → stubAllSterm 응답
 */
@SpringBootTest
class InvenSearchServiceTest {

    static WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        wireMock.start();
        registry.add("inven.board.url", () -> wireMock.baseUrl() + "/board/lostark/5355");
        registry.add("inven.search.max-iterations", () -> "3"); // 첫 페이지 + 병렬 2페이지
    }

    @AfterAll
    static void stopWireMock() { wireMock.stop(); }

    @BeforeEach
    void resetStubs() { wireMock.resetAll(); }

    @Autowired
    InvenSearchService searchService;

    // -----------------------------------------------------------------------
    // 스텁 헬퍼
    // -----------------------------------------------------------------------

    /** 첫 페이지 (sterm 없음) - 먼저 등록 → 낮은 우선순위 */
    private void stubFirstPage(String svalue, String bodyFile) {
        wireMock.stubFor(get(urlPathEqualTo("/board/lostark/5355"))
                .withQueryParam("stype", equalTo("content"))
                .withQueryParam("svalue", equalTo(svalue))
                .willReturn(ok().withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBodyFile(bodyFile)));
    }

    /** sterm이 있는 모든 요청 - 나중 등록 → 높은 우선순위 */
    private void stubAllStermPages(String svalue, String bodyFile) {
        wireMock.stubFor(get(urlPathEqualTo("/board/lostark/5355"))
                .withQueryParam("stype", equalTo("content"))
                .withQueryParam("svalue", equalTo(svalue))
                .withQueryParam("sterm", matching(".+"))
                .willReturn(ok().withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBodyFile(bodyFile)));
    }

    /** 게시글 상세 페이지 스텁 */
    private void stubPostDetail(int postId, String bodyFile) {
        wireMock.stubFor(get(urlPathEqualTo("/board/lostark/5355/" + postId))
                .willReturn(ok().withHeader("Content-Type", "text/html; charset=UTF-8")
                        .withBodyFile(bodyFile)));
    }

    // -----------------------------------------------------------------------
    // matchesTargetSection 단위 테스트
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("matchesTargetSection - 대상자 패턴 매칭")
    class MatchesTargetSectionTest {

        @Test @DisplayName("인라인 콤마 구분 - 공백 있음")
        void inline_commaWithSpace() {
            String text = "대상자: 교쩌, 교쩌1, 교쩌2, 교쩌3";
            assertThat(searchService.matchesTargetSection(text, "교쩌")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "교쩌3")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "교쩌4")).isFalse();
        }

        @Test @DisplayName("인라인 콤마 구분 - 공백 없음")
        void inline_commaNoSpace() {
            String text = "대상자:교쩌,교쩌1,교쩌2,교쩌3";
            assertThat(searchService.matchesTargetSection(text, "교쩌")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "없는닉")).isFalse();
        }

        @Test @DisplayName("멀티라인 - 개행만으로 구분")
        void multiline_newlineOnly() {
            String text = "대상자:  교쩌\n교쩌1\n교쩌2\n교쩌3";
            assertThat(searchService.matchesTargetSection(text, "교쩌")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "교쩌4")).isFalse();
        }

        @Test @DisplayName("멀티라인 - 콤마+개행 혼합")
        void multiline_commaAndNewline() {
            String text = "대상자:  교쩌,\n교쩌1,\n교쩌2,\n교쩌3";
            assertThat(searchService.matchesTargetSection(text, "교쩌")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "없는닉")).isFalse();
        }

        @Test @DisplayName("공백 구분 - 같은 줄에 공백으로 나열")
        void spaceSeparated_singleLine() {
            String text = "대상자: 무적돌진 무적최강홀나 홀나는죽지않는다 무적홀나";
            assertThat(searchService.matchesTargetSection(text, "무적돌진")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "없는닉")).isFalse();
        }

        @Test @DisplayName("공백 구분 - '닉네임' 라벨 다음 줄에 공백으로 나열 (실제 포스트 패턴)")
        void spaceSeparated_afterLabelLine() {
            String text = "대상자: 닉네임\n무적돌진 무적최강홀나 홀나는죽지않는다 무적홀나 무적랏폿 무적서폿 ";
            assertThat(searchService.matchesTargetSection(text, "무적돌진")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "없는닉")).isFalse();
        }

        @Test @DisplayName("공백·콤마·개행 혼합")
        void mixed_separators() {
            String text = "대상자: 교쩌 교쩌1,교쩌2\n교쩌3";
            assertThat(searchService.matchesTargetSection(text, "교쩌")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "교쩌3")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "교쩌4")).isFalse();
        }

        @Test @DisplayName("■ 이후 내용은 섹션에 포함하지 않음")
        void doesNotMatchBeyondNextSection() {
            String text = "대상자: 교쩌\n\n■ 사건 설명\n다른닉네임";
            assertThat(searchService.matchesTargetSection(text, "교쩌")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "다른닉네임")).isFalse();
        }

        @Test @DisplayName("■ 없이 '사건' 키워드로 시작하는 줄에서 종료 (비정형 게시글)")
        void terminatesAtSagunKeywordWithoutMarker() {
            // ■ 없이 "사건 설명" 섹션이 이어지는 경우
            String text = "대상자: 오픈\n\n사건 설명\n오픈톡방에서 오픈 어쩌고";
            assertThat(searchService.matchesTargetSection(text, "오픈")).isTrue();
            // "사건 설명" 이후의 "오픈"(오픈톡)은 포함되지 않아야 함
            assertThat(searchService.matchesTargetSection(text, "오픈톡방에서")).isFalse();
        }

        @Test @DisplayName("■ 없이 '증거' 키워드로 시작하는 줄에서 종료")
        void terminatesAtJeungGeoKeyword() {
            String text = "대상자: 닉네임A\n\n증거 자료\n닉네임B";
            assertThat(searchService.matchesTargetSection(text, "닉네임A")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "닉네임B")).isFalse();
        }

        @Test @DisplayName("템플릿 라벨 '닉네임' 다음 단락의 실제 닉네임은 여전히 매칭")
        void templateLabelFollowedByActualNicknames() {
            // "대상자: 닉네임\n\n실제닉네임1 실제닉네임2" 구조
            String text = "대상자: 닉네임\n\n무적돌진 무적최강홀나 홀나는죽지않는다\n■ 사건 설명\n다른내용";
            assertThat(searchService.matchesTargetSection(text, "무적돌진")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "홀나는죽지않는다")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "다른내용")).isFalse();
        }

        @Test @DisplayName("실제 포스트 구조 - 별도 div의 텍스트 (extractPostText 처리 후)")
        void actualPostStructure_separateDivs() {
            String text = "작성자: 닉네임\n햇님이랑달님이랑기상이랑\n\n\n대상자: 닉네임\n"
                        + "무적돌진 무적최강홀나 홀나는죽지않는다 무적홀나 무적랏폿 무적서폿 \n\n\n\n■ 사건 설명\n";
            assertThat(searchService.matchesTargetSection(text, "무적돌진")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "없는닉")).isFalse();
            assertThat(searchService.matchesTargetSection(text, "사건")).isFalse();
        }

        @Test @DisplayName("부분 일치 제외 - 정확히 일치해야 함")
        void exactMatchOnly() {
            String text = "대상자: BeeA23";
            assertThat(searchService.matchesTargetSection(text, "BeeA23")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "BeeA")).isFalse();
            assertThat(searchService.matchesTargetSection(text, "BeeA23님")).isFalse();
        }

        @Test
        @DisplayName("빈 줄 이후 내용은 대상자 섹션에 포함하지 않음 (실제 사례: 207121 오픈가능)")
        void blankLineTerminatesSection_preventsFalseMatch() {
            // 게시글 207121 구조: 대상자 이후 빈 줄, 그 다음에 본문 내용 ("오픈 가능")
            String text =
                "작성자 : 니나브 / 연수망나뇽\n" +
                "대상자 : 카제로스 / 낯선블레이드\n\n" + // ← 빈 줄로 섹션 종료
                "가토 소나벨 1페이지 파폭 쌀먹으로 파괴 실패\n" +
                "궁금하면 친구목록 오픈 가능\n";

            assertThat(searchService.matchesTargetSection(text, "카제로스")).isTrue();
            assertThat(searchService.matchesTargetSection(text, "낯선블레이드")).isTrue();
            // 빈 줄 이후 본문의 "오픈"은 매칭되지 않아야 함
            assertThat(searchService.matchesTargetSection(text, "오픈")).isFalse();
        }

        @Test
        @DisplayName("템플릿 라벨 '닉네임' 다음 단락의 실제 닉네임은 여전히 매칭")
        void doesNotMatchFromJakSeongMokJeokSection() {
            // 실제 게시글 234829 구조:
            // 작성 목적: "오픈톡으로 욕설" → 오픈 이라고 검색하면 오탐 발생 가능
            // 게임 닉네임 / 대상자: 방패NK, 대장NK, ...
            String text =
                "■ 작성 목적\n" +
                "태초 섬에서 오픈 오픈톡으로 비응신련아 라고 초대함\n\n" +
                "■ 게임 닉네임\n" +
                "작성자: 닉네임\n어떤작성자\n\n" +
                "대상자: 닉네임\n\n" +
                "방패NK 대장NK 고수NK\n\n" +
                "■ 사건 설명\n" +
                "오픈톡으로 욕설보냄\n";

            // "게임 닉네임" 섹션의 대상자에만 있는 닉네임은 매칭
            assertThat(searchService.matchesTargetSection(text, "방패NK")).isTrue();

            // "작성 목적" 섹션에만 있는 단어는 매칭 안 됨
            assertThat(searchService.matchesTargetSection(text, "오픈")).isFalse();
            assertThat(searchService.matchesTargetSection(text, "오픈톡으로")).isFalse();
        }

        @Test
        @DisplayName("닉네임이 다른 글자에 붙어있으면 미매칭 - '오픈가능' 에서 '오픈' 오탐 방지")
        void adjacentKoreanPreventsMatch() {
            String text = "■ 게임 닉네임\n대상자: 닉네임\n\n오픈가능";
            assertThat(searchService.matchesTargetSection(text, "오픈")).isFalse();

            // 영문도 동일하게 처리
            String text2 = "■ 게임 닉네임\n대상자: 닉네임\n\nBeeA23Plus";
            assertThat(searchService.matchesTargetSection(text2, "BeeA23")).isFalse();
        }

        @Test
        @DisplayName("독립 단어로 존재하면 매칭 - 앞뒤가 구분자면 OK")
        void standaloneWordMatches() {
            // 콤마·공백·개행으로 구분된 경우는 매칭
            assertThat(searchService.matchesTargetSection(
                    "■ 게임 닉네임\n대상자: 닉네임\n\n오픈 고수NK", "오픈")).isTrue();
            assertThat(searchService.matchesTargetSection(
                    "■ 게임 닉네임\n대상자: 오픈,고수NK", "오픈")).isTrue();
        }

        @Test @DisplayName("대상자 섹션 없으면 false")
        void noTargetSection_returnsFalse() {
            assertThat(searchService.matchesTargetSection("작성자: 바닐라밀크티\n내용", "BeeA23")).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // 통합 검색 테스트
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BeeA23 검색 - 대상자 섹션 일치 게시글만 반환")
    void search_BeeA23_onlyReturnsPostsWithMatchingTargetSection() throws IOException {
        stubFirstPage("BeeA23", "inven-search-result.html");
        stubAllStermPages("BeeA23", "inven-search-empty.html");
        stubPostDetail(235181, "inven-post-with-target.html");    // 대상자: BeeA23 → 포함
        stubPostDetail(235100, "inven-post-without-target.html"); // 대상자: 다른닉 → 제외

        List<BoardPost> results = searchService.search("BeeA23");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("자존심이 밥먹여주진 않습니다 아만서버 BeeA23님");
        assertThat(results.get(0).getAuthor()).isEqualTo("바닐라밀크티");
        assertThat(results.get(0).getDate()).isEqualTo("02-27");
        assertThat(results.get(0).getViews()).isEqualTo("125");
        assertThat(results.get(0).getRecommends()).isEqualTo("2");
    }

    @Test
    @DisplayName("대상자 여러 명 - 닉네임이 목록 중 하나면 포함")
    void search_multipleTargets_includesIfNicknameInList() throws IOException {
        stubFirstPage("BeeA23", "inven-search-result.html");
        stubAllStermPages("BeeA23", "inven-search-empty.html");
        stubPostDetail(235181, "inven-post-multi-target.html"); // 대상자: BeeA23 BeeA24 BeeA25
        stubPostDetail(235100, "inven-post-without-target.html");

        List<BoardPost> results = searchService.search("BeeA23");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getLink()).contains("/235181");
    }

    @Test
    @DisplayName("모든 게시글이 대상자 섹션 불일치이면 빈 결과")
    void search_allPostsFiltered_returnsEmpty() throws IOException {
        stubFirstPage("BeeA23", "inven-search-result.html");
        stubAllStermPages("BeeA23", "inven-search-empty.html");
        stubPostDetail(235181, "inven-post-without-target.html");
        stubPostDetail(235100, "inven-post-without-target.html");

        assertThat(searchService.search("BeeA23")).isEmpty();
    }

    @Test
    @DisplayName("첫 페이지에 next-sterm 없으면 추가 요청 없음")
    void search_noNextLink_onlyOneRequest() throws IOException {
        stubFirstPage("BeeA23", "inven-search-empty.html");

        searchService.search("BeeA23");

        // 병렬 페이지 요청 없음 (게시글 목록 요청 1회만)
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/board/lostark/5355")));
    }

    @Test
    @DisplayName("병렬 페이지 fetch - next-sterm 있으면 나머지 페이지 동시 요청")
    void search_parallelPageFetch_requestsAllPages() throws IOException {
        // 첫 페이지: next sterm=9774864 포함
        stubFirstPage("BeeA23", "inven-search-result.html");
        stubAllStermPages("BeeA23", "inven-search-empty.html");
        stubPostDetail(235181, "inven-post-with-target.html");
        stubPostDetail(235100, "inven-post-with-target.html");

        searchService.search("BeeA23");

        // max-iterations=3 → 첫 페이지(1) + 병렬 2페이지 = 총 3회
        wireMock.verify(3, getRequestedFor(urlPathEqualTo("/board/lostark/5355")));
        // 병렬 페이지의 sterm은 9774864, 9784864 (기반 sterm + 0*10000, +1*10000)
        wireMock.verify(getRequestedFor(urlPathEqualTo("/board/lostark/5355"))
                .withQueryParam("sterm", equalTo("9774864")));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/board/lostark/5355"))
                .withQueryParam("sterm", equalTo("9784864")));
    }

    @Test
    @DisplayName("게시글 상세 fetch 실패 시 해당 게시글 제외 후 계속")
    void search_postDetailFetchFails_skipsPost() throws IOException {
        stubFirstPage("BeeA23", "inven-search-result.html");
        stubAllStermPages("BeeA23", "inven-search-empty.html");
        wireMock.stubFor(get(urlPathEqualTo("/board/lostark/5355/235181"))
                .willReturn(aResponse().withStatus(404)));
        stubPostDetail(235100, "inven-post-with-target.html");

        List<BoardPost> results = searchService.search("BeeA23");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getLink()).contains("/235100");
    }

    @Test
    @DisplayName("검색 URL에 stype=content, svalue={nickname} 포함 확인")
    void search_usesCorrectQueryParams() throws IOException {
        stubFirstPage("BeeA23", "inven-search-empty.html");

        searchService.search("BeeA23");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/board/lostark/5355"))
                .withQueryParam("stype", equalTo("content"))
                .withQueryParam("svalue", equalTo("BeeA23")));
    }

    @Test
    @DisplayName("인증 아이콘 img alt 텍스트가 작성자에 포함되지 않아야 함")
    void search_author_excludesMapleIconAltText() throws IOException {
        stubFirstPage("BeeA23", "inven-search-result.html");
        stubAllStermPages("BeeA23", "inven-search-empty.html");
        stubPostDetail(235181, "inven-post-with-target.html");
        stubPostDetail(235100, "inven-post-with-target.html");

        List<BoardPost> results = searchService.search("BeeA23");

        assertThat(results.stream().map(BoardPost::getAuthor))
                .allSatisfy(author -> assertThat(author).doesNotContain("인증 아이콘"));
    }
}
