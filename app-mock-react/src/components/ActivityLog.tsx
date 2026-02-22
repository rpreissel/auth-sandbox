import { useEffect, useRef, useState } from 'react';
import type { LogEntry } from '../types';

interface Props {
  entries: LogEntry[];
  onClear: () => void;
}

const levelColor: Record<LogEntry['level'], string> = {
  info: 'text-sky-300',
  ok:   'text-green-400',
  err:  'text-red-400',
  warn: 'text-amber-400',
};

export default function ActivityLog({ entries, onClear }: Props) {
  const bottomRef  = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (open) bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [entries, open]);

  return (
    <div className="rounded-2xl border border-[--color-border]/60 bg-[--color-surface]/60 overflow-hidden">
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-center gap-2 px-4 py-3 text-left hover:bg-[--color-surface2]/50 transition-colors"
      >
        <span className="text-xs font-medium text-[--color-text-dim]">Aktivitätslog</span>
        {entries.length > 0 && (
          <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded-full bg-[--color-accent]/20 text-[--color-accent]">
            {entries.length}
          </span>
        )}
        <span className="ml-auto text-[--color-text-dim] text-xs">{open ? '▲' : '▼'}</span>
      </button>

      {open && (
        <div className="border-t border-[--color-border]/60">
          <div className="px-4 py-1.5 flex justify-end">
            <button
              onClick={onClear}
              className="text-[10px] text-[--color-text-dim] hover:text-[--color-text] transition-colors"
            >
              Leeren
            </button>
          </div>
          <div className="px-4 pb-3 font-mono text-[11px] max-h-40 overflow-y-auto flex flex-col gap-1.5">
            {entries.length === 0 && (
              <span className="text-[--color-text-dim]">Keine Einträge.</span>
            )}
            {entries.map(e => (
              <div key={e.id} className="flex gap-3">
                <span className="text-[--color-text-dim] shrink-0 opacity-60">{e.ts}</span>
                <span className={levelColor[e.level]}>{e.msg}</span>
              </div>
            ))}
            <div ref={bottomRef} />
          </div>
        </div>
      )}
    </div>
  );
}
