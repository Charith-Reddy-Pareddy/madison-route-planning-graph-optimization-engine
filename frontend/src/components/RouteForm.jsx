export default function RouteForm({
  nodes,
  startId,
  endId,
  mode,
  onModeChange,
  onStartChange,
  onEndChange,
  onSubmit,
  onSwap,
  loading,
}) {
  const sorted = [...nodes].sort((a, b) => a.name.localeCompare(b.name));

  return (
    <form onSubmit={onSubmit}>
      <fieldset className="mode-toggle">
        <legend>Travel mode</legend>
        <label>
          <input type="radio" name="mode" value="walk" checked={mode === 'walk'} onChange={() => onModeChange('walk')} />
          Walk
        </label>
        <label>
          <input type="radio" name="mode" value="drive" checked={mode === 'drive'} onChange={() => onModeChange('drive')} />
          Drive
        </label>
      </fieldset>
      <label>
        Start
        <select value={startId} onChange={(e) => onStartChange(e.target.value)} required>
          {sorted.map((node) => (
            <option key={node.id} value={node.id}>
              {node.name}
            </option>
          ))}
        </select>
      </label>
      <button type="button" className="swap-button" onClick={onSwap} aria-label="Swap start and end">
        ⇅ Swap
      </button>
      <label>
        End
        <select value={endId} onChange={(e) => onEndChange(e.target.value)} required>
          {sorted.map((node) => (
            <option key={node.id} value={node.id}>
              {node.name}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={loading}>
        {loading ? 'Calculating...' : 'Find shortest route'}
      </button>
    </form>
  );
}
