package com.nauty.p3d.panel;

import android.content.Context;

import com.nauty.p3d.leia.LeiaBackend;

/**
 * github 배포판: Leia 백엔드({@link LeiaBackend}, src/leiabridge/)가 앱에 그대로
 * 컴파일돼 있으니 바로 만든다. play 배포판은 같은 클래스를 기기의 CalibrationK68 APK 와
 * 함께 런타임에 올린다 — src/play/.../panel/Panel.java 참고.
 */
public final class Panel {

    /**
     * glass(갤럭시 S25 Ultra + SpatialGlass 필름)는 Leia 가 아니다 — 위빙과 눈 추적을
     * 우리가 직접 한다. 그 백엔드는 glass 소스셋에만 있어서(MediaPipe 의존) 이름으로 만든다.
     */
    private static final String GLASS_BACKEND = "com.nauty.p3d.glass.GlassBackend";

    public static PanelBackend create(Context ctx) {
        if ("glass".equals(com.nauty.p3d.BuildConfig.FLAVOR_device)) {
            try {
                return (PanelBackend) Class.forName(GLASS_BACKEND).newInstance();
            } catch (Exception e) {
                throw new IllegalStateException("glass 백엔드를 만들 수 없다", e);
            }
        }
        return new LeiaBackend();
    }

    private Panel() {}
}
