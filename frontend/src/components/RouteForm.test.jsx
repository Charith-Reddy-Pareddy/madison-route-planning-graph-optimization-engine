import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import RouteForm from './RouteForm';

const nodes = [
  { id: 'b', name: 'Bascom Hill' },
  { id: 'a', name: 'Capitol Square' },
];

describe('RouteForm', () => {
  it('lists intersections alphabetically by name, not by id order', () => {
    render(
      <RouteForm nodes={nodes} startId="a" endId="b" onStartChange={() => {}} onEndChange={() => {}} onSubmit={() => {}} onSwap={() => {}} loading={false} />,
    );
    const options = screen.getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(['Bascom Hill', 'Capitol Square', 'Bascom Hill', 'Capitol Square']);
  });

  it('calls onSubmit when the form is submitted', () => {
    const onSubmit = vi.fn((e) => e.preventDefault());
    render(
      <RouteForm nodes={nodes} startId="a" endId="b" onStartChange={() => {}} onEndChange={() => {}} onSubmit={onSubmit} onSwap={() => {}} loading={false} />,
    );
    fireEvent.click(screen.getByRole('button', { name: /find shortest route/i }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('calls onSwap when the swap button is clicked, without submitting the form', () => {
    const onSwap = vi.fn();
    const onSubmit = vi.fn();
    render(
      <RouteForm nodes={nodes} startId="a" endId="b" onStartChange={() => {}} onEndChange={() => {}} onSubmit={onSubmit} onSwap={onSwap} loading={false} />,
    );
    fireEvent.click(screen.getByRole('button', { name: /swap start and end/i }));
    expect(onSwap).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('disables the submit button while loading, so a slow request cannot be double-submitted', () => {
    render(
      <RouteForm nodes={nodes} startId="a" endId="b" onStartChange={() => {}} onEndChange={() => {}} onSubmit={() => {}} onSwap={() => {}} loading={true} />,
    );
    expect(screen.getByRole('button', { name: /calculating/i })).toBeDisabled();
  });

  it('calls onModeChange with the newly selected travel mode', () => {
    const onModeChange = vi.fn();
    render(
      <RouteForm
        nodes={nodes}
        startId="a"
        endId="b"
        mode="walk"
        onModeChange={onModeChange}
        onStartChange={() => {}}
        onEndChange={() => {}}
        onSubmit={() => {}}
        onSwap={() => {}}
        loading={false}
      />,
    );
    fireEvent.click(screen.getByRole('radio', { name: /drive/i }));
    expect(onModeChange).toHaveBeenCalledWith('drive');
  });
});
