// Matches the backend's estimate for bus travel (RoadNetwork.BUS_MPH) --
// used here only for the grouped bus-trip times, since the server's
// per-leg `minutes` is always walking pace (see PathFinderServer).
const BUS_MPH = 12;

function busMinutes(miles) {
  return Math.max(1, Math.round((miles / BUS_MPH) * 60));
}

/**
 * Collapses consecutive segments riding the same bus route into a single
 * boarding-to-alighting trip, e.g. three "Route A" legs in a row become one
 * "Route A: Capitol Square -> Library Mall" trip instead of three lines.
 */
function groupBusTrips(segments) {
  const trips = [];
  for (const segment of segments) {
    const last = trips[trips.length - 1];
    if (!segment.busRoute) continue;
    if (last && last.busRoute === segment.busRoute && last.to === segment.from) {
      last.to = segment.to;
      last.miles = Math.round((last.miles + segment.miles) * 100) / 100;
    } else {
      trips.push({ busRoute: segment.busRoute, from: segment.from, to: segment.to, miles: segment.miles });
    }
  }
  return trips;
}

export default function RouteResult({ statusType, statusMessage, route, nodesById }) {
  const nameOf = (id) => nodesById.get(id)?.name ?? id;
  const busTrips = route ? groupBusTrips(route.segments) : [];

  return (
    <>
      <div className={statusType === 'error' ? 'status error' : 'status'} role="status" aria-live="polite">
        {statusMessage}
      </div>

      {route && route.path.length > 0 && (
        <div className="route-endpoints">
          <span className="endpoint-tag start">{nameOf(route.path[0].id)}</span>
          <span aria-hidden="true">&rarr;</span>
          <span className="endpoint-tag end">{nameOf(route.path[route.path.length - 1].id)}</span>
        </div>
      )}

      {route && (
        <>
          <h3 className="section-label">Walking directions</h3>
          <ol className="steps">
            {route.segments.map((segment, i) => (
              <li key={`${segment.from}-${segment.to}-${i}`}>
                {nameOf(segment.from)} &rarr; {nameOf(segment.to)} ({segment.miles} mi, {segment.minutes} min)
              </li>
            ))}
            <li className="total">
              Total: {route.totalMiles} mi, ~{route.totalMinutes} min on foot
            </li>
          </ol>

          <h3 className="section-label">Bus trips along this route</h3>
          {busTrips.length > 0 ? (
            <ul className="bus-trips">
              {busTrips.map((trip, i) => (
                <li key={`${trip.from}-${trip.to}-${i}`}>
                  <span className="mode-tag bus">{trip.busRoute}</span> {nameOf(trip.from)} &rarr; {nameOf(trip.to)} (
                  {trip.miles} mi, ~{busMinutes(trip.miles)} min by bus)
                </li>
              ))}
            </ul>
          ) : (
            <p className="no-bus">No bus route covers this trip -- it's a walk the whole way.</p>
          )}

          <p className="transit-disclaimer">
            Bus suggestions are illustrative, based on Metro Transit's published routes -- check{' '}
            <a href="https://www.cityofmadison.com/metro/routes-schedules" target="_blank" rel="noreferrer">
              Metro Transit
            </a>{' '}
            for live times.
          </p>
        </>
      )}
    </>
  );
}
