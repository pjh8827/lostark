import React, { useEffect, useMemo, useRef, useState } from "react";

type Roi = { xPct: number; yPct: number; wPct: number; hPct: number };

function clamp01(v: number) {
  return Math.max(0, Math.min(1, v));
}

function pctToPx(roi: Roi, width: number, height: number) {
  const x = Math.round(clamp01(roi.xPct) * width);
  const y = Math.round(clamp01(roi.yPct) * height);
  const w = Math.round(clamp01(roi.wPct) * width);
  const h = Math.round(clamp01(roi.hPct) * height);
  return { x, y, w, h };
}

export default function App() {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const fullCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const roiCanvasRef = useRef<HTMLCanvasElement | null>(null);

  const [stream, setStream] = useState<MediaStream | null>(null);
  const [isSharing, setIsSharing] = useState(false);
  const [fps, setFps] = useState(0);
  const [lastShot, setLastShot] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 임시 ROI 기본값 (처음엔 슬라이더로 맞추면 됨)
  const [roi, setRoi] = useState<Roi>({
    xPct: 0.62,
    yPct: 0.18,
    wPct: 0.35,
    hPct: 0.62,
  });

  const roiLabel = useMemo(() => {
    const x = Math.round(roi.xPct * 100);
    const y = Math.round(roi.yPct * 100);
    const w = Math.round(roi.wPct * 100);
    const h = Math.round(roi.hPct * 100);
    return `ROI: x=${x}%, y=${y}%, w=${w}%, h=${h}%`;
  }, [roi]);

  async function startShare() {
    setError(null);
    setLastShot(null);

    try {
      const s = await navigator.mediaDevices.getDisplayMedia({
        video: { frameRate: 15 } as MediaTrackConstraints,
        audio: false,
      });

      const videoTrack = s.getVideoTracks()[0];
      videoTrack.onended = () => stopShare();

      if (videoRef.current) {
        videoRef.current.srcObject = s;
        await videoRef.current.play();
      }

      setStream(s);
      setIsSharing(true);
    } catch (e: any) {
      setError(
        e?.name === "NotAllowedError"
          ? "화면 공유 권한이 거부됐어요."
          : `화면 공유 시작 실패: ${e?.message ?? String(e)}`
      );
      setIsSharing(false);
      setStream(null);
    }
  }

  function stopShare() {
    if (stream) {
      for (const t of stream.getTracks()) t.stop();
    }
    setStream(null);
    setIsSharing(false);
  }

  useEffect(() => {
    let raf = 0;
    let frames = 0;
    let lastFpsTs = performance.now();

    const draw = () => {
      raf = requestAnimationFrame(draw);

      const video = videoRef.current;
      const fullCanvas = fullCanvasRef.current;
      const roiCanvas = roiCanvasRef.current;
      if (!video || !fullCanvas || !roiCanvas) return;

      const vw = video.videoWidth;
      const vh = video.videoHeight;
      if (!vw || !vh) return;

      if (fullCanvas.width !== vw || fullCanvas.height !== vh) {
        fullCanvas.width = vw;
        fullCanvas.height = vh;
      }

      const fctx = fullCanvas.getContext("2d");
      if (!fctx) return;

      fctx.drawImage(video, 0, 0, vw, vh);

      const { x, y, w, h } = pctToPx(roi, vw, vh);

      // ROI 박스 표시
      fctx.save();
      fctx.lineWidth = 4;
      fctx.strokeStyle = "rgba(0, 255, 0, 0.9)";
      fctx.strokeRect(x, y, w, h);
      fctx.restore();

      // ROI 렌더
      if (roiCanvas.width !== w || roiCanvas.height !== h) {
        roiCanvas.width = Math.max(1, w);
        roiCanvas.height = Math.max(1, h);
      }
      const rctx = roiCanvas.getContext("2d");
      if (!rctx) return;
      rctx.drawImage(video, x, y, w, h, 0, 0, w, h);

      // FPS
      frames += 1;
      const now = performance.now();
      if (now - lastFpsTs >= 1000) {
        setFps(frames);
        frames = 0;
        lastFpsTs = now;
      }
    };

    if (isSharing) raf = requestAnimationFrame(draw);
    return () => raf && cancelAnimationFrame(raf);
  }, [isSharing, roi, stream]);

  function takeSnapshot() {
    const roiCanvas = roiCanvasRef.current;
    if (!roiCanvas) return;
    setLastShot(roiCanvas.toDataURL("image/png"));
  }

  return (
    <div style={{ fontFamily: "system-ui, sans-serif", padding: 16, maxWidth: 1100, margin: "0 auto" }}>
      <h1 style={{ margin: "8px 0 4px" }}>로아 젬 스캐너 POC (React)</h1>
      <p style={{ marginTop: 0, color: "#555" }}>
        1) <b>젬 스캔 시작</b> → 2) 로스트아크 창 선택 → 3) 젬 목록 화면 띄우기 → 4) ROI 조절 → 5) Snapshot
      </p>

      <div style={{ display: "flex", gap: 12, flexWrap: "wrap", alignItems: "center" }}>
        {!isSharing ? (
          <button onClick={startShare} style={{ padding: "10px 14px", cursor: "pointer" }}>
            젬 스캔 시작
          </button>
        ) : (
          <button onClick={stopShare} style={{ padding: "10px 14px", cursor: "pointer" }}>
            공유 중지
          </button>
        )}

        <button onClick={takeSnapshot} disabled={!isSharing} style={{ padding: "10px 14px", cursor: "pointer" }}>
          ROI Snapshot
        </button>

        <span style={{ color: "#333" }}>
          상태: {isSharing ? "공유 중" : "대기"} / FPS: {fps}
        </span>

        <span style={{ color: "#333" }}>{roiLabel}</span>
      </div>

      {error && (
        <div style={{ marginTop: 12, padding: 10, background: "#ffecec", color: "#a40000", borderRadius: 8 }}>
          {error}
        </div>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "1fr 360px", gap: 16, marginTop: 16 }}>
        <div>
          <h2 style={{ margin: "8px 0" }}>전체 화면</h2>
          <canvas
            ref={fullCanvasRef}
            style={{ width: "100%", borderRadius: 12, border: "1px solid #ddd", background: "#111" }}
          />
          <video ref={videoRef} style={{ display: "none" }} playsInline />
        </div>

        <div>
          <h2 style={{ margin: "8px 0" }}>ROI 미리보기</h2>
          <canvas
            ref={roiCanvasRef}
            style={{ width: "100%", borderRadius: 12, border: "1px solid #ddd", background: "#111" }}
          />

          <div style={{ marginTop: 14, padding: 12, border: "1px solid #eee", borderRadius: 12 }}>
            <h3 style={{ marginTop: 0 }}>ROI 조절</h3>

            <Slider label="x(좌)" value={roi.xPct} onChange={(v) => setRoi((r) => ({ ...r, xPct: v }))} />
            <Slider label="y(상)" value={roi.yPct} onChange={(v) => setRoi((r) => ({ ...r, yPct: v }))} />
            <Slider
              label="w(너비)"
              value={roi.wPct}
              min={0.05}
              onChange={(v) => setRoi((r) => ({ ...r, wPct: v }))}
            />
            <Slider
              label="h(높이)"
              value={roi.hPct}
              min={0.05}
              onChange={(v) => setRoi((r) => ({ ...r, hPct: v }))}
            />

            <p style={{ color: "#666", marginBottom: 0 }}>
              팁: 처음엔 <b>창모드 1920×1080</b>로 하면 ROI가 훨씬 안정적이야.
            </p>
          </div>

          {lastShot && (
            <div style={{ marginTop: 14 }}>
              <h3 style={{ margin: "8px 0" }}>마지막 Snapshot</h3>
              <img src={lastShot} alt="roi snapshot" style={{ width: "100%", borderRadius: 12, border: "1px solid #ddd" }} />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function Slider(props: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  min?: number;
  max?: number;
}) {
  const min = props.min ?? 0;
  const max = props.max ?? 1;

  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
        <span>{props.label}</span>
        <span style={{ color: "#444" }}>{Math.round(props.value * 100)}%</span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={0.005}
        value={props.value}
        onChange={(e) => props.onChange(Number(e.target.value))}
        style={{ width: "100%" }}
      />
    </div>
  );
}