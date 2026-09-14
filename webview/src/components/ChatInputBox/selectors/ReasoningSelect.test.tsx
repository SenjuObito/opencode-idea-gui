import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ReasoningSelect } from './ReasoningSelect';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, options?: { defaultValue?: string }) => options?.defaultValue ?? _key,
  }),
}));

describe('ReasoningSelect', () => {
  it('shows and selects max for Codex', () => {
    const onChange = vi.fn();

    render(
      <ReasoningSelect
        value={'xhigh'}
        onChange={onChange}
        currentProvider={'codex'}
        selectedModel={'gpt-5.6-sol'}
      />,
    );

    fireEvent.click(screen.getByRole('button'));
    fireEvent.click(screen.getByText('Max'));

    expect(onChange).toHaveBeenCalledWith('max');
  });

  it('shows max for namespaced GPT-5.6 Codex models regardless of case', () => {
    render(
      <ReasoningSelect
        value={'xhigh'}
        onChange={vi.fn()}
        currentProvider={'codex'}
        selectedModel={'PPIO/PA/GPT-5.6-SOL'}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('Max')).toBeTruthy();
  });

  it('keeps max hidden when modelVariants excludes it', () => {
    render(
      <ReasoningSelect
        value={'xhigh'}
        onChange={vi.fn()}
        currentProvider={'codex'}
        selectedModel={'gpt-5.5'}
        modelVariants={['low', 'medium', 'high', 'xhigh']}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.queryByText('Max')).toBeNull();
  });

  it('shows all levels when modelVariants is empty (no filtering)', () => {
    render(
      <ReasoningSelect
        value={'xhigh'}
        onChange={vi.fn()}
        currentProvider={'codex'}
        modelVariants={[]}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('Max')).toBeTruthy();
  });

  it('shows max for custom GPT-5.6 model suffixes', () => {
    render(
      <ReasoningSelect
        value={'xhigh'}
        onChange={vi.fn()}
        currentProvider={'codex'}
        selectedModel={'ppio/pa/gpt-5.6-sol-preview'}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('Max')).toBeTruthy();
  });

  it('shows xhigh and max for Claude Opus 4.8', () => {
    render(
      <ReasoningSelect
        value="high"
        onChange={vi.fn()}
        currentProvider="claude"
        selectedModel="claude-opus-4-8"
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('XHigh')).toBeTruthy();
    expect(screen.getByText('Max')).toBeTruthy();
  });

  it('shows max but not xhigh for Claude Sonnet 4.6 (via modelVariants)', () => {
    render(
      <ReasoningSelect
        value="high"
        onChange={vi.fn()}
        currentProvider="claude"
        selectedModel="claude-sonnet-4-6"
        modelVariants={['low', 'medium', 'high', 'max']}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.queryByText('XHigh')).toBeNull();
    expect(screen.getByText('Max')).toBeTruthy();
  });

  it('shows max but not xhigh for Claude Sonnet 5 (via modelVariants)', () => {
    render(
      <ReasoningSelect
        value="high"
        onChange={vi.fn()}
        currentProvider="claude"
        selectedModel="claude-sonnet-5"
        modelVariants={['low', 'medium', 'high', 'max']}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.queryByText('XHigh')).toBeNull();
    expect(screen.getByText('Max')).toBeTruthy();
  });

  it('resets unavailable effort when modelVariants excludes current value', () => {
    const onChange = vi.fn();

    render(
      <ReasoningSelect
        value="xhigh"
        onChange={onChange}
        currentProvider="claude"
        selectedModel="claude-sonnet-4-6"
        modelVariants={['low', 'medium', 'high', 'max']}
      />,
    );

    expect(onChange).toHaveBeenCalledWith('high');
  });

  it('shows all levels when modelVariants is empty (source treats it as no filter)', () => {
    render(
      <ReasoningSelect
        value="high"
        onChange={vi.fn()}
        currentProvider="claude"
        selectedModel="claude-haiku-4-5"
        modelVariants={[]}
      />,
    );

    expect(screen.queryByRole('button')).toBeTruthy();
  });
});
