import { describe, it, expect } from 'vitest';

describe('PasswordModal validation', () => {
  const validatePassword = (password: string, confirm: string): string | null => {
    if (password.length < 8) {
      return 'Passwort muss mindestens 8 Zeichen haben.';
    }
    if (password !== confirm) {
      return 'Passwörter stimmen nicht überein.';
    }
    return null;
  };

  it('rejects password shorter than 8 characters', () => {
    expect(validatePassword('short', 'short')).toBe('Passwort muss mindestens 8 Zeichen haben.');
    expect(validatePassword('1234567', '1234567')).toBe('Passwort muss mindestens 8 Zeichen haben.');
  });

  it('accepts exactly 8 characters', () => {
    expect(validatePassword('12345678', '12345678')).toBeNull();
  });

  it('accepts password longer than 8 characters', () => {
    expect(validatePassword('123456789', '123456789')).toBeNull();
  });

  it('rejects when passwords do not match', () => {
    expect(validatePassword('password123', 'password124')).toBe('Passwörter stimmen nicht überein.');
  });
});
