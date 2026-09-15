export default function RouteResult({ statusType, statusMessage, route, nodesById }) {
  return (
    <>
      <div className={statusType === 'error' ? 'status error' : 'status'} role="status" aria-live="polite">
        {statusMessage}
      </div>
      {route && (
        <ol className="steps">
          {route.segments.map((segment, i) => {
            const from = nodesById.get(segment.from)?.name ?? segment.from;
            const to = nodesById.get(segment.to)?.name ?? segment.to;
            return (
              <li key={`${segment.from}-${segment.to}-${i}`}>
                <span className={segment.busRoute ? 'mode-tag bus' : 'mode-tag walk'}>
                  {segment.busRoute ?? 'Walk'}
                </span>{' '}
                {from} &rarr; {to} ({segment.miles} mi, {segment.minutes} min)
              </li>
            );
          })}
          <li className="total">
            Total: {route.totalMiles} mi, ~{route.totalMinutes} min
          </li>
        </ol>
      )}
      {route && (
        <p className="transit-disclaimer">
          Bus suggestions are illustrative, based on Metro Transit's published routes -- check{' '}
          <a href="https://www.cityofmadison.com/metro/routes-schedules" target="_blank" rel="noreferrer">
            Metro Transit
          </a>{' '}
          for live times.
        </p>
      )}
    </>
  );
}
