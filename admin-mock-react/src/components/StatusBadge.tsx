import type { StatusKind } from '../types';

interface Props {
  text: string;
  kind: StatusKind;
}

const statusStyles: Record<StatusKind, string> = {
  idle: 'status-idle',
  success: 'status-success',
  error: 'status-error',
  pending: 'status-pending',
};

const statusLabels: Record<StatusKind, string> = {
  idle: 'Ready',
  success: 'Authenticated',
  error: 'Error',
  pending: '',
};

export function StatusBadge({ text, kind }: Props) {
  const label = text || statusLabels[kind];
  return (
    <span className={`status ${statusStyles[kind]}`}>
      <span className="status-dot" />
      {label}
    </span>
  );
}
