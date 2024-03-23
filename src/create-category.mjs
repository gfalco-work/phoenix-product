import aws from 'aws-sdk';
import awsXRay from 'aws-xray-sdk';
import {DynamoDBClient} from "@aws-sdk/client-dynamodb";
import {
  DynamoDBDocumentClient,
  PutCommand,
} from "@aws-sdk/lib-dynamodb";

const client = new DynamoDBClient({});
const dynamo = DynamoDBDocumentClient.from(client);
const tableName = "OnlineShop";

awsXRay.captureAWS(aws);

export async function handler(event) {

  let body;
  let statusCode = 200;
  const headers = {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST"
  };
  try {
    let requestJSON = JSON.parse(event.body);

    const segment = awsXRay.getSegment();
    const subSegment = segment.addNewSubsegment('PutEventInDynamoDb');
    subSegment.addAnnotation('product id', requestJSON.id);
    subSegment.addMetadata('product', requestJSON);

    body = await dynamo.send(
        new PutCommand({
          TableName: tableName,
          Item: {
            PK: 'CATEGORY#' + requestJSON.id,
            SK: requestJSON.name,
            name: requestJSON.name,
            description: requestJSON.description
          },
        })
    );

    subSegment.close();

    body = `Put item ${requestJSON.id}`;
  } catch (err) {
    statusCode = 400;
    body = err.message;
  } finally {
    body = JSON.stringify(body);
  }

  return {statusCode, body, headers};
}