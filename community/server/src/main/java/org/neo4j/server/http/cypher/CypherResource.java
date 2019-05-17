/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.http.cypher;

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.logging.Log;
import org.neo4j.server.http.cypher.format.api.InputEventStream;
import org.neo4j.server.http.cypher.format.api.TransactionUriScheme;
import org.neo4j.server.rest.Neo4jError;
import org.neo4j.server.rest.dbms.AuthorizedRequestWrapper;
import org.neo4j.server.rest.web.HttpConnectionInfoFactory;

import static org.neo4j.server.web.HttpHeaderUtils.getTransactionTimeout;

@Path( "/{databaseName}/transaction" )
public class CypherResource
{

    private final HttpTransactionManager httpTransactionManager;
    private final TransactionUriScheme uriScheme;
    private final Log log;
    private final HttpHeaders headers;
    private final HttpServletRequest request;
    private final String databaseName;

    public CypherResource( @Context HttpTransactionManager httpTransactionManager,
            @Context UriInfo uriInfo,
            @Context Log log,
            @Context HttpHeaders headers,
            @Context HttpServletRequest request,
            @PathParam( "databaseName" ) String databaseName )
    {
        this.httpTransactionManager = httpTransactionManager;
        this.databaseName = databaseName;
        this.uriScheme = new TransactionUriBuilder( uriInfo, databaseName );
        this.log = log;
        this.headers = headers;
        this.request = request;
    }

    @POST
    public Response executeStatementsInNewTransaction( InputEventStream inputEventStream )
    {
        InputEventStream inputStream = ensureNotNull( inputEventStream );

        Optional<TransactionFacade> transactionFacade = httpTransactionManager.getTransactionFacade( databaseName );

        return transactionFacade
                .map( t -> {
                    TransactionHandle transactionHandle = createNewTransactionHandle( t, headers, request, false );

                    Invocation invocation = new Invocation( log, transactionHandle, uriScheme.txCommitUri( transactionHandle.getId() ), inputStream, false );
                    OutputEventStreamImpl outputStream =
                            new OutputEventStreamImpl( inputStream.getParameters(), t.getTransactionContainer(), uriScheme, invocation::execute );
                    return Response.created( transactionHandle.uri() ).entity( outputStream ).build();
                } )
                .orElse( createNonExistentDatabaseResponse( inputStream.getParameters() ) );
    }

    @POST
    @Path( "/{id}" )
    public Response executeStatements( @PathParam( "id" ) long id, InputEventStream inputEventStream, @Context UriInfo uriInfo,
            @Context HttpServletRequest request )
    {
        return executeInExistingTransaction( id, inputEventStream, false );
    }

    @POST
    @Path( "/{id}/commit" )
    public Response commitTransaction( @PathParam( "id" ) long id, InputEventStream inputEventStream )
    {
        return executeInExistingTransaction( id, inputEventStream, true );
    }

    @POST
    @Path( "/commit" )
    public Response commitNewTransaction( @Context HttpHeaders headers, InputEventStream inputEventStream, @Context HttpServletRequest request )
    {
        InputEventStream inputStream = ensureNotNull( inputEventStream );

        Optional<TransactionFacade> transactionFacade = httpTransactionManager.getTransactionFacade( databaseName );
        return transactionFacade
                .map( t -> {
                    TransactionHandle transactionHandle = createNewTransactionHandle( t, headers, request, true );

                    Invocation invocation = new Invocation( log, transactionHandle, null, inputStream, true );
                    OutputEventStreamImpl outputStream =
                            new OutputEventStreamImpl( inputStream.getParameters(), t.getTransactionContainer(), uriScheme, invocation::execute );
                    return Response.ok( outputStream ).build();
                }  )
                .orElse( createNonExistentDatabaseResponse( inputStream.getParameters() ) );
    }

    @DELETE
    @Path( "/{id}" )
    public Response rollbackTransaction( @PathParam( "id" ) final long id )
    {
        Optional<TransactionFacade> transactionFacade = httpTransactionManager.getTransactionFacade( databaseName );
        return transactionFacade.map( t -> {
            TransactionHandle transactionHandle;
            try
            {
                transactionHandle = t.terminate( id );
            }
            catch ( TransactionLifecycleException e )
            {
                return invalidTransaction( t, e, Collections.emptyMap() );
            }

            RollbackInvocation invocation = new RollbackInvocation( log, transactionHandle );
            OutputEventStreamImpl outputEventStream =
                    new OutputEventStreamImpl( Collections.emptyMap(), null, uriScheme, invocation::execute );
            return Response.ok().entity( outputEventStream ).build();

        } ).orElse( createNonExistentDatabaseResponse( Collections.emptyMap() ) );
    }

    private TransactionHandle createNewTransactionHandle( TransactionFacade transactionFacade, HttpHeaders headers, HttpServletRequest request,
            boolean implicitTransaction )
    {
        LoginContext loginContext = AuthorizedRequestWrapper.getLoginContextFromHttpServletRequest( request );
        long customTransactionTimeout = getTransactionTimeout( headers, log );
        ClientConnectionInfo connectionInfo = HttpConnectionInfoFactory.create( request );
        return transactionFacade.newTransactionHandle( uriScheme, implicitTransaction, loginContext, connectionInfo, customTransactionTimeout );
    }

    private Response executeInExistingTransaction( long transactionId, InputEventStream inputEventStream, boolean finishWithCommit )
    {
        InputEventStream inputStream = ensureNotNull( inputEventStream );

        Optional<TransactionFacade> transactionFacade = httpTransactionManager.getTransactionFacade( databaseName );
        return transactionFacade.map( t -> {
            TransactionHandle transactionHandle;
            try
            {
                transactionHandle = t.findTransactionHandle( transactionId );
            }
            catch ( TransactionLifecycleException e )
            {
                return invalidTransaction( t, e, inputStream.getParameters() );
            }
            Invocation invocation =
                    new Invocation( log, transactionHandle, uriScheme.txCommitUri( transactionHandle.getId() ), inputStream, finishWithCommit );
            OutputEventStreamImpl outputEventStream =
                    new OutputEventStreamImpl( inputStream.getParameters(), t.getTransactionContainer(), uriScheme, invocation::execute );
            return Response.ok( outputEventStream ).build();
        } ).orElse( createNonExistentDatabaseResponse( inputStream.getParameters() ) );
    }

    private Response invalidTransaction( TransactionFacade transactionFacade, TransactionLifecycleException e, Map<String,Object> parameters )
    {
        ErrorInvocation errorInvocation = new ErrorInvocation( e.toNeo4jError() );
        return Response.status( Response.Status.NOT_FOUND ).entity(
                new OutputEventStreamImpl( parameters, transactionFacade.getTransactionContainer(), uriScheme, errorInvocation::execute ) ).build();
    }

    private InputEventStream ensureNotNull( InputEventStream inputEventStream )
    {
        if ( inputEventStream != null )
        {
            return inputEventStream;
        }

        return InputEventStream.EMPTY;
    }

    private Response createNonExistentDatabaseResponse( Map<String,Object> parameters )
    {
        ErrorInvocation errorInvocation = new ErrorInvocation( new Neo4jError( Status.Database.DatabaseNotFound,
                String.format( "The database requested does not exists. Requested database name: '%s'.", databaseName ) ) );
        return Response.status( Response.Status.NOT_FOUND ).entity(
                new OutputEventStreamImpl( parameters, null, uriScheme, errorInvocation::execute ) ).build();
    }

    private static class TransactionUriBuilder implements TransactionUriScheme
    {
        private final URI dbUri;
        private final URI cypherUri;

        TransactionUriBuilder( UriInfo uriInfo, String databaseName )
        {
            UriBuilder builder = uriInfo.getBaseUriBuilder().path( databaseName );
            dbUri = builder.build();
            cypherUri = builder.path( "transaction" ).build();
        }

        @Override
        public URI txUri( long id )
        {
            return transactionBuilder( id ).build();
        }

        @Override
        public URI txCommitUri( long id )
        {
            return transactionBuilder( id ).path( "/commit" ).build();
        }

        @Override
        public URI dbUri()
        {
            return dbUri;
        }

        private UriBuilder transactionBuilder( long id )
        {
            return UriBuilder.fromUri( cypherUri ).path( "/" + id );
        }
    }
}