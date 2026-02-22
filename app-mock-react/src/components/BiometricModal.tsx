import { useEffect, useRef, useId } from 'react';

interface Props {
  onConfirm: () => void;
  onCancel: () => void;
}

export default function BiometricModal({ onConfirm, onCancel }: Props) {
  const ringId = useId();
  const fillRef = useRef<SVGCircleElement>(null);

  // Restart animation each time modal mounts
  useEffect(() => {
    const el = fillRef.current;
    if (!el) return;
    el.style.animation = 'none';
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        el.style.animation = 'fill-ring 1.5s ease-in-out forwards';
      });
    });
  }, []);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm">
      <div className="animate-slide-up bg-[--color-surface] border border-[--color-border] rounded-[20px] p-10 text-center max-w-xs w-[90%]">
        <span className="text-6xl block mb-4">🔐</span>
        <h3 className="text-lg font-bold mb-2">Biometrische Authentifizierung</h3>
        <p className="text-sm text-[--color-text-dim] mb-6">
          Fingerabdruck oder Face&nbsp;ID bestätigen, um die Challenge zu signieren.
        </p>

        <svg className="w-16 h-16 mx-auto mb-6" viewBox="0 0 60 60" aria-hidden="true">
          <circle
            cx="30" cy="30" r="25"
            fill="none" strokeWidth="5"
            stroke="var(--color-border)"
            strokeDasharray="157"
          />
          <circle
            ref={fillRef}
            id={ringId}
            cx="30" cy="30" r="25"
            fill="none" strokeWidth="5"
            stroke="var(--color-accent)"
            strokeDasharray="157"
            strokeDashoffset="157"
            strokeLinecap="round"
            transform="rotate(-90 30 30)"
          />
        </svg>

        <div className="flex gap-3 justify-center">
          <button
            onClick={onConfirm}
            className="px-5 py-2 rounded-lg bg-[--color-accent] text-white font-semibold text-sm hover:opacity-90 transition-opacity"
          >
            Bestätigen
          </button>
          <button
            onClick={onCancel}
            className="px-5 py-2 rounded-lg bg-[--color-surface2] border border-[--color-border] text-[--color-text] font-semibold text-sm hover:opacity-90 transition-opacity"
          >
            Abbrechen
          </button>
        </div>
      </div>
    </div>
  );
}
