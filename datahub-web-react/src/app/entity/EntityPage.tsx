import React, { useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { EntityType } from '../../types.generated';
import { useTrackPageView } from '../analytics/useTrackPageView';
import { BrowsableEntityPage } from '../browse/BrowsableEntityPage';
import LineageExplorer from '../lineage/LineageExplorer';
import useIsLineageMode from '../lineage/utils/useIsLineageMode';
import { SearchablePage } from '../search/SearchablePage';
import { useEntityRegistry } from '../useEntityRegistry';
import analytics, { EventType } from '../analytics';

interface RouteParams {
    urn: string;
}

interface Props {
    entityType: EntityType;
}

/**
 * Responsible for rendering an Entity Profile
 */
export const EntityPage = ({ entityType }: Props) => {
    useTrackPageView();
    const { urn } = useParams<RouteParams>();
    const entityRegistry = useEntityRegistry();
    const isBrowsable = entityRegistry.getEntity(entityType).isBrowseEnabled();
    const isLineageSupported = entityRegistry.getEntity(entityType).isLineageEnabled();
    const ContainerPage = isBrowsable || isLineageSupported ? BrowsableEntityPage : SearchablePage;
    const isLineageMode = useIsLineageMode();
    useEffect(() => {
        analytics.event({
            type: EventType.EntityViewEvent,
            entityType,
            urn,
        });
    }, [entityType, urn]);

    return (
        <ContainerPage isBrowsable={isBrowsable} urn={urn} type={entityType} lineageSupported={isLineageSupported}>
            {isLineageMode && isLineageSupported ? (
                <LineageExplorer type={entityType} urn={urn} />
            ) : (
                entityRegistry.renderProfile(entityType, urn)
            )}
        </ContainerPage>
    );
};
