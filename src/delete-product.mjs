import aws from 'aws-sdk';
import awsXRay from 'aws-xray-sdk';
import {DynamoDBClient} from "@aws-sdk/client-dynamodb";
import {
  DynamoDBDocumentClient,
  DeleteCommand,
} from "@aws-sdk/lib-dynamodb";

const client = new DynamoDBClient({});
const dynamo = DynamoDBDocumentClient.from(client);
const tableName = "ProductTable";

awsXRay.captureAWS(aws);

export async function handler(event) {
  let body;
  let statusCode = 200;

  try {
    const segment = awsXRay.getSegment();
    const subSegment = segment.addNewSubsegment('DeleteEventInDynamoDb');
    subSegment.addAnnotation('product id', event.pathParameters.id);

    body = await dynamo.send(
        new DeleteCommand({
          TableName: tableName,
          Key: {
            id: event.pathParameters.id,
          },
        })
    );

    subSegment.close();
    body = `Deleted item ${event.pathParameters.id}`;
  } catch (err) {
    statusCode = 400;
    body = err.message;
  } finally {
    body = JSON.stringify(body);
  }
  const headers = {
    "Access-Control-Allow-Headers" : "Content-Type",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "OPTIONS,DELETE"
  };
  return {statusCode, body, headers};
}
