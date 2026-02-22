import type { ReactNode } from 'react';

interface CardProps {
  title: ReactNode;
  apiUrl?: string;
  children: ReactNode;
}

export function Card({ title, apiUrl, children }: CardProps) {
  return (
    <div className="rounded-xl border border-[--color-border] bg-[--color-surface] overflow-hidden">
      <div className="flex items-center gap-2 px-4 py-2.5 bg-[--color-surface2] border-b border-[--color-border]">
        <span className="text-xs font-semibold uppercase tracking-widest text-[--color-text-dim]">
          {title}
        </span>
        {apiUrl && (
          <span className="ml-auto font-mono text-[10px] bg-[--color-bg] border border-[--color-border] rounded px-1.5 py-0.5 text-[--color-text-dim]">
            {apiUrl}
          </span>
        )}
      </div>
      <div className="p-5">{children}</div>
    </div>
  );
}

// ── Shared button variants ────────────────────────────────────────

type BtnVariant = 'primary' | 'secondary' | 'danger' | 'ghost';

interface BtnProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: BtnVariant;
  size?: 'sm' | 'md';
}

const variantCls: Record<BtnVariant, string> = {
  primary:   'bg-[--color-accent] text-white hover:opacity-90',
  secondary: 'bg-[--color-surface2] border border-[--color-border] text-[--color-text] hover:opacity-90',
  danger:    'bg-red-950 border border-red-800 text-red-400 hover:opacity-90',
  ghost:     'text-[--color-text-dim] hover:text-[--color-text]',
};

const sizeCls: Record<'sm' | 'md', string> = {
  sm: 'px-3 py-1.5 text-xs',
  md: 'px-4 py-2.5 text-sm',
};

export function Btn({ variant = 'primary', size = 'md', className = '', ...rest }: BtnProps) {
  return (
    <button
      {...rest}
      className={[
        'inline-flex items-center gap-2 rounded-lg font-semibold transition-opacity disabled:opacity-40 disabled:cursor-not-allowed',
        variantCls[variant],
        sizeCls[size],
        className,
      ].join(' ')}
    />
  );
}

// ── Labelled input ─────────────────────────────────────────────────

interface FieldProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label: string;
  required?: boolean;
}

export function Field({ label, required, ...rest }: FieldProps) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-[11px] font-semibold uppercase tracking-wider text-[--color-text-dim]">
        {label}{required && <span className="text-red-400 ml-0.5">*</span>}
      </span>
      <input
        {...rest}
        className="bg-[--color-bg] border border-[--color-border] rounded-lg px-3 py-2 text-sm font-mono text-[--color-text] outline-none focus:border-[--color-accent] transition-colors placeholder:text-[--color-text-dim]/50"
      />
    </label>
  );
}

// ── Mono value cell ────────────────────────────────────────────────

interface InfoCellProps {
  label: string;
  value: ReactNode;
  span?: boolean;
}

export function InfoCell({ label, value, span }: InfoCellProps) {
  return (
    <div className={`bg-[--color-bg] border border-[--color-border] rounded-lg p-3 ${span ? 'col-span-2' : ''}`}>
      <div className="text-[10px] font-semibold uppercase tracking-wider text-[--color-text-dim] mb-1">{label}</div>
      <div className="font-mono text-xs text-[--color-text] break-all">{value}</div>
    </div>
  );
}
