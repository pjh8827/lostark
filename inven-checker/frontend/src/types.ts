export interface BoardPost {
  title: string;
  link: string;
  author: string;
  date: string;
  views: string;
  recommends: string;
  matchedNickname?: string; // 강화 검색 모드에서 이 게시글을 발견한 캐릭터 닉네임
}

export interface DetectRegion {
  x: number; y: number; w: number; h: number;
}

export interface OcrResponse {
  nicknames: string[];
  groups: string[][];
  engine: string;
  party_finder_detected: boolean;
  detect_region?: DetectRegion;
}
