import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from './App';
import { getGraph, getRoute } from './api';

vi.mock('./api', () => ({
  getGraph: vi.fn(),
  getRoute: vi.fn(),
}));

const graph = {
  nodes: [
    { id: 'grainger_hall', name: 'Grainger Hall', lat: 43.0727, lon: -89.4016 },
    { id: 'state_frances', name: 'State & Frances', lat: 43.074, lon: -89.397 },
  ],
  edges: [{ from: 'grainger_hall', to: 'state_frances', walkMiles: 0.44, accessibleMiles: 0.46 }],
};

afterEach(() => vi.clearAllMocks());

describe('accessible mode in the route form', () => {
  it('requests and renders the accessible walking route when selected', async () => {
    getGraph.mockResolvedValue(graph);
    getRoute.mockImplementation(async (start, end, mode) => ({
      path: [
        { id: start, name: 'Grainger Hall' },
        { id: end, name: 'State & Frances' },
      ],
      segments: [{ from: start, to: end, miles: 0.46, minutes: 8, busRoute: null }],
      totalMiles: 0.46,
      totalMinutes: 8,
      mode,
    }));

    render(<App />);
    await screen.findByRole('heading', { name: /walking directions/i });
    fireEvent.click(screen.getByRole('radio', { name: 'Accessible' }));

    await screen.findByRole('heading', { name: /accessible walking directions \(no stairs, no steep inclines\)/i });
    expect(getRoute).toHaveBeenLastCalledWith('grainger_hall', 'state_frances', 'accessible', false);
    expect(screen.getByText('Grainger Hall → State & Frances (0.46 mi, 8 min)')).toBeInTheDocument();
  });
});
